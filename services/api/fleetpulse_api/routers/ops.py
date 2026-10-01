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
from ..auth import CurrentUser, Manager, PlatformAdmin
from ..db import ensure_user, tenant_tx
from ..errors import ApiError
from ..ratelimit import RateLimited, rate_limit
from .maintenance import record_result

router = APIRouter(prefix="/v1/ops", tags=["operations"])

Component = Literal["COOLING", "IGNITION", "ELECTRICAL", "EV_BATTERY", "TRANSMISSION", "BRAKES"]


async def _simulator(request: Request, method: str, path: str, json_body: Any = None, **params: Any) -> Any:
    settings = request.app.state.settings
    if not settings.demo_controls:
        raise ApiError(404, "Not found", "demo controls are disabled")
    try:
        async with httpx.AsyncClient(base_url=settings.simulator_url, timeout=10) as client:
            resp = await client.request(method, path, params=params, json=json_body)
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
    # run's events; the ts bound only limits the scan. No FINAL: duplicates are removed before
    # storage (normaliser dedup, exactly-once sink), so a plain count is the honest check (a
    # duplicate would show as stored above sent), and FINAL over an hour-long run exceeded the
    # API user's per-query memory limit.
    rows = await request.app.state.clickhouse.query(user, """
        SELECT oem, count() AS stored
        FROM fleet.telemetry
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


@router.post("/sensor-fault")
async def sensor_fault(request: Request, user: Manager, _: RateLimited,
                       count: Annotated[int, Query(ge=1, le=20)] = 3,
                       minutes: Annotated[int, Query(ge=1, le=60)] = 10) -> dict[str, Any]:
    """Breaks the coolant *sensor* (not the engine) on a few of the caller's vehicles."""
    result = await _simulator(request, "POST", "/admin/sensor-fault", count=count, minutes=minutes,
                              tenant=user.tenant_id)
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        await audit.record(conn, request, user, "demo.sensor_fault", "simulator", None,
                           {"vehicles": len(result["vins"]), "minutes": minutes})
    return result


@router.post("/firmware")
async def firmware(request: Request, user: PlatformAdmin, _: RateLimited,
                   mph: Annotated[bool, Query()] = True) -> dict[str, Any]:
    """A firmware bug at Aurora: speed sent in mph but labelled kph. Affects every tenant's Aurora
    vehicles, so only a platform operator can trigger it."""
    result = await _simulator(request, "POST", "/admin/firmware", oem="AURORA", mph=str(mph).lower())
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        await audit.record(conn, request, user, "demo.firmware_bug" if mph else "demo.firmware_fixed", "simulator",
                           None, result)
    return result


# The simulator's ground-truth components, named as the model names parts.
_PART = {"COOLING": "Cooling system", "IGNITION": "Ignition / misfire", "ELECTRICAL": "12 V electrical",
         "EV_BATTERY": "High-voltage battery", "TRANSMISSION": "Transmission", "BRAKES": "ABS / brakes"}


@router.post("/workshop-results")
async def workshop_results(request: Request, user: Manager, _: RateLimited,
                           limit: Annotated[int, Query(ge=1, le=500)] = 100) -> dict[str, Any]:
    """Demo stand-in for the workshop system: inspects the caller's next ``limit`` booked vehicles
    in the simulator (which knows each vehicle's real fault) and records what a mechanic would find.
    Vehicles outside the live simulation have no ground truth and are left booked."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        booked = await (await conn.execute("""
            SELECT b.id, trim(v.vin) AS vin FROM service_booking b JOIN vehicle v ON v.id = b.vehicle_id
            WHERE b.status = 'SCHEDULED' ORDER BY b.scheduled_for, b.id LIMIT %s""", (limit,))).fetchall()
    if not booked:
        return {"recorded": 0, "faults_found": 0, "not_simulated": 0}
    truth: dict[str, str] = await _simulator(request, "POST", "/admin/inspect", json_body=[b["vin"] for b in booked])
    recorded = found = 0
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        for b in booked:
            if b["vin"] not in truth:
                continue
            part = _PART.get(truth[b["vin"]])
            await record_result(conn, b["id"], part is not None, part)
            recorded += 1
            found += part is not None
        await audit.record(conn, request, user, "demo.workshop_results", "service_booking", None,
                           {"recorded": recorded, "faults_found": found})
    return {"recorded": recorded, "faults_found": found, "not_simulated": len(booked) - recorded}
