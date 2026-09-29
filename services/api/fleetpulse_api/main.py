import logging
import re
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from redis.asyncio import Redis
from starlette.exceptions import HTTPException as StarletteHTTPException

from .auth import TokenVerifier, build_verifier
from .clickhouse import ClickHouse
from .config import Settings, get_settings
from .db import create_pool
from .errors import ApiError, api_error_handler, http_error_handler, validation_error_handler
from .ratelimit import RateLimiter
from .routers import alerts, copilot, live, me, onboarding, ops, risk, vehicles

log = logging.getLogger("fleetpulse.api")

REQUESTS = Counter("api_requests_total", "HTTP requests", ["method", "route", "status"])
LATENCY = Histogram("api_request_seconds", "HTTP request latency", ["method", "route"],
                    buckets=(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5))
_REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


def create_app(settings: Settings | None = None, verifier: TokenVerifier | None = None) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.settings = settings
        app.state.verifier = verifier or build_verifier(settings)
        app.state.pg = create_pool(settings)
        await app.state.pg.open(wait=True, timeout=15)   # fail the boot, not the first request
        app.state.redis = Redis.from_url(settings.redis_url, decode_responses=True)
        app.state.clickhouse = ClickHouse(settings)
        app.state.limiter = RateLimiter(app.state.redis, settings.rate_limit_per_second, settings.rate_limit_burst)
        app.state.live_map_cache = {}
        try:
            yield
        finally:
            await app.state.clickhouse.close()
            await app.state.redis.aclose()
            await app.state.pg.close()

    app = FastAPI(title="FleetPulse API", version="1.0.0", lifespan=lifespan,
                  description="Tenant-isolated access to fleets, live vehicle state, telemetry and alerts.")
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)

    @app.middleware("http")
    async def request_context(request: Request, call_next):  # type: ignore[no-untyped-def]
        incoming = request.headers.get("x-request-id", "")
        request.state.request_id = incoming if _REQUEST_ID.match(incoming) else uuid.uuid4().hex
        start = time.perf_counter()
        try:
            response: Response = await call_next(request)
        except Exception:
            log.exception("unhandled error on %s %s", request.method, request.url.path)
            response = JSONResponse({"type": "about:blank", "title": "Internal error", "status": 500,
                                     "request_id": request.state.request_id},
                                    status_code=500, media_type="application/problem+json")
        route = request.scope.get("route")
        template = getattr(route, "path", "unmatched")   # label by template, not raw path
        REQUESTS.labels(request.method, template, str(response.status_code)).inc()
        LATENCY.labels(request.method, template).observe(time.perf_counter() - start)
        response.headers["X-Request-ID"] = request.state.request_id
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    # Level 5: the live map (~700 KB raw at 10K vehicles) compresses ~3x faster than at 9, ~10% larger.
    app.add_middleware(GZipMiddleware, minimum_size=2048, compresslevel=5)
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_methods=["GET", "POST"],
                       allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
                       expose_headers=["X-Request-ID", "Retry-After", "X-RateLimit-Remaining"])

    for r in (me.router, vehicles.router, alerts.router, live.router, ops.router, onboarding.router, risk.router,
              copilot.router):
        app.include_router(r)

    @app.get("/healthz", include_in_schema=False)
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/readyz", include_in_schema=False)
    async def readyz(request: Request) -> JSONResponse:
        checks = {"clickhouse": await request.app.state.clickhouse.ping()}
        try:
            checks["redis"] = bool(await request.app.state.redis.ping())
        except Exception:
            checks["redis"] = False
        try:
            async with request.app.state.pg.connection() as conn:
                await conn.execute("SELECT 1")
            checks["postgres"] = True
        except Exception:
            checks["postgres"] = False
        return JSONResponse({"ready": all(checks.values()), "checks": checks},
                            status_code=200 if all(checks.values()) else 503)

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app


app = create_app()
