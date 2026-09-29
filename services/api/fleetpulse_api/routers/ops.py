"""Pipeline reconciliation and demo controls.

``GET /v1/ops/pipeline`` compares what the simulator sent for the caller's tenant (its ledger
of valid, unique events, excluding the duplicates and corrupt records it sends on purpose)
with what reached ClickHouse for that tenant in the same run. Equal counts mean nothing was
lost or duplicated end to end.

The POST endpoints drive the simulator for demos. They exist only while ``demo_controls``
is on, need the fleet_manager role, act only on the caller's own vehicles, and are audited.
"""

from typing import Annotated, Any, Literal

import httpx
from fastapi import APIRouter, Query, Request
from pydantic import BaseModel, Field

from .. import audit
from ..auth import CurrentUser, Manager
from ..db import ensure_user, tenant_tx
from ..errors import ApiError
from ..ratelimit import RateLimited, rate_limit

router = APIRouter(prefix="/v1/ops", tags=["operations"])

Component = Literal["COOLING", "IGNITION", "ELECTRICAL", "EV_BATTERY", "TRANSMISSION", "BRAKES"]


async def _simulator(request: Request, method: str, path: str, **params: Any) -> dict[str, Any]:
    settings = request.app.state.settings
    if not settings.demo_controls:
        raise ApiError(404, "Not found", "demo controls are disabled")
    try:
        async with httpx.AsyncClient(base_url=settings.simulator_url, timeout=10) as client:
            resp = await client.request(method, path, params=params)
    except httpx.HTTPError:
        raise ApiError(503, "Simulator unavailable", "the vehicle simulator is not running") from None
    if resp.status_code == 503:
        raise ApiError(503, "Simulator unavailable", "the simulator is not streaming (live mode not started)")
    if resp.status_code != 200:
        raise ApiError(502, "Simulator error", resp.text[:200])
    return resp.json()


@router.get("/pipeline")
async def pipeline(request: Request, user: CurrentUser,
                   _: Annotated[None, rate_limit(cost=3)]) -> dict[str, Any]:
    stats = await _simulator(request, "GET", "/admin/stats")
    sent: dict[str, int] = stats.get("ledgerByTenant", {}).get(user.tenant_id, {})
    # Sequence numbers restart above the run's start time, so seq > firstSeq is exactly this
    # run's events; the ts bound only limits the scan.
    rows = await request.app.state.clickhouse.query(user, """
        SELECT oem, count() AS stored
        FROM fleet.telemetry FINAL
        WHERE seq > {first_seq:UInt64} AND ts >= {started:DateTime} - INTERVAL 10 MINUTE
        GROUP BY oem""", {"first_seq": stats["firstSeq"], "started": stats["startedAt"][:19].replace("T", " ")})
    stored = {r["oem"]: int(r["stored"]) for r in rows}
    oems = sorted(set(sent) | set(stored))
    per_oem = [{"oem": o, "sent": sent.get(o, 0), "stored": stored.get(o, 0)} for o in oems]
    total_sent, total_stored = sum(sent.values()), sum(stored.values())
    return {
        "started_at": stats["startedAt"], "paused": stats.get("paused", False),
        "burst_factor": stats.get("burstFactor", 1), "burst_until": stats.get("burstUntil"),
        "sent": total_sent, "stored": total_stored, "in_flight": total_sent - total_stored,
        "per_oem": per_oem,
        "chaos_sent": {"duplicates": stats.get("duplicatesSent", 0), "invalid": stats.get("invalidSent", 0),
                       "out_of_order": stats.get("outOfOrderSent", 0)},
    }


class InjectRequest(BaseModel):
    component: Component
    count: int = Field(10, ge=1, le=50)
    minutes: int = Field(3, ge=1, le=60)


@router.post("/inject")
async def inject(body: InjectRequest, request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    """Makes ``count`` of the caller's vehicles develop ``component`` faults over ``minutes``."""
    result = await _simulator(request, "POST", "/admin/inject", component=body.component, count=body.count,
                              minutes=body.minutes, tenant=user.tenant_id)
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        await audit.record(conn, request, user, "demo.inject_fault", "simulator", None,
                           {"component": body.component, "vehicles": len(result["vins"]), "minutes": body.minutes})
    return {"component": body.component, "vins": result["vins"]}


@router.post("/burst")
async def burst(request: Request, user: Manager, _: RateLimited,
                factor: Annotated[int, Query(ge=2, le=10)] = 3,
                seconds: Annotated[int, Query(ge=10, le=600)] = 120) -> dict[str, Any]:
    """Multiplies the whole simulated fleet's reporting rate for a while (a traffic spike)."""
    result = await _simulator(request, "POST", "/admin/burst", factor=factor, seconds=seconds)
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        await audit.record(conn, request, user, "demo.burst", "simulator", None, result)
    return result


@router.post("/pause")
async def pause(request: Request, user: Manager, _: RateLimited,
                paused: Annotated[bool, Query()] = True) -> dict[str, Any]:
    """Stops (or resumes) new traffic, so sent and stored counts can settle to an exact match."""
    result = await _simulator(request, "POST", "/admin/pause", paused=str(paused).lower())
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        await audit.record(conn, request, user, "demo.pause" if paused else "demo.resume", "simulator", None)
    return result
