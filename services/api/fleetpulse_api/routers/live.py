import time
from typing import Annotated, Any

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse

from ..auth import CurrentUser
from ..db import tenant_tx
from ..errors import ApiError
from ..ratelimit import rate_limit

router = APIRouter(prefix="/v1", tags=["live"])

_FIELDS = ["vin", "lat", "lon", "status", "speed_kmh", "ts"]


def _parse_bbox(bbox: str | None) -> tuple[float, float, float, float]:
    if bbox is None:
        return -180.0, -85.0, 180.0, 85.0
    try:
        min_lon, min_lat, max_lon, max_lat = (float(x) for x in bbox.split(","))
    except ValueError:
        raise ApiError(422, "Invalid request", "bbox is minLon,minLat,maxLon,maxLat") from None
    if not (-180 <= min_lon < max_lon <= 180 and -85 <= min_lat < max_lat <= 85):
        raise ApiError(422, "Invalid request", "bbox out of range or inverted")
    return min_lon, min_lat, max_lon, max_lat


@router.get("/live/vehicles")
async def live_vehicles(
    request: Request, user: CurrentUser,
    _: Annotated[None, rate_limit(cost=5)],
    bbox: Annotated[str | None, Query(description="minLon,minLat,maxLon,maxLat")] = None,
    limit: Annotated[int, Query(ge=1, le=60_000)] = 20_000,
) -> JSONResponse:
    """Latest position and status of the caller's vehicles inside a map viewport, as
    column-oriented rows (the whole fleet is 50K vehicles; field names are sent once)."""
    min_lon, min_lat, max_lon, max_lat = _parse_bbox(bbox)
    rows = []
    for vin, (lat, lon, status, speed, ts) in (await _live_map(request, user.tenant_id)).items():
        if min_lon <= lon <= max_lon and min_lat <= lat <= max_lat:
            rows.append([vin, lat, lon, status, speed, ts])
    rows.sort(key=lambda r: r[0])   # stable order, so a truncated view does not flicker between polls
    # Already plain JSON types: skip FastAPI's per-value encoder, which dominates at 50K rows.
    return JSONResponse({"fields": _FIELDS, "rows": rows[:limit], "truncated": len(rows) > limit})


LiveEntry = tuple[float, float, str, float, int]


async def _live_map(request: Request, tenant_id: str) -> dict[str, LiveEntry]:
    """The tenant's live map read model (one hash, maintained by the stream processor), cached
    for a second so that every open dashboard polling the map shares one Redis read."""
    cache: dict[str, tuple[float, dict[str, LiveEntry]]] = request.app.state.live_map_cache
    hit = cache.get(tenant_id)
    if hit and time.monotonic() - hit[0] < 1.0:
        return hit[1]
    raw = await request.app.state.redis.hgetall(f"map:{tenant_id}")
    parsed: dict[str, LiveEntry] = {}
    for vin, entry in raw.items():
        lat, lon, status, speed, ts = entry.split(",")
        parsed[vin] = (round(float(lat), 5), round(float(lon), 5), status, float(speed), int(ts))
    cache[tenant_id] = (time.monotonic(), parsed)
    return parsed


@router.get("/fleet/summary")
async def fleet_summary(request: Request, user: CurrentUser,
                        _: Annotated[None, rate_limit(cost=2)]) -> dict[str, Any]:
    """Headline numbers for the dashboard: live status counts (Redis read model) and open
    alerts by severity (Postgres)."""
    live: dict[str, int] = {}
    for _lat, _lon, status, _speed, _ts in (await _live_map(request, user.tenant_id)).values():
        live[status] = live.get(status, 0) + 1
    async with tenant_tx(request.app.state.pg, user) as conn:
        alerts = await (await conn.execute("""
            SELECT r.severity, count(*) AS n FROM alert a JOIN alert_rule r ON r.code = a.rule_code
            WHERE a.status = 'OPEN' GROUP BY r.severity""")).fetchall()
        vehicles = (await (await conn.execute("SELECT count(*) AS n FROM vehicle")).fetchone())["n"]
    return {"vehicles": vehicles, "reporting": sum(live.values()), "live_status": live,
            "open_alerts": {r["severity"]: r["n"] for r in alerts}}
