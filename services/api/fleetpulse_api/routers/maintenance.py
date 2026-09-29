"""Workshop planning from risk scores: a capacity-aware service plan, booking it, and a parts forecast."""

from datetime import UTC, date, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel, Field

from .. import audit
from ..auth import CurrentUser, Manager, Principal
from ..db import ensure_user, tenant_tx
from ..errors import ApiError
from ..maintenance import Candidate, Depot, first_plan_day, parts_forecast, plan, plan_summary, slot_time
from ..ratelimit import RateLimited

router = APIRouter(prefix="/v1/maintenance", tags=["maintenance"])

DEFAULT_INSPECTION_COST = 150.0
MAX_BOOKINGS_PER_REQUEST = 500

_LATEST = """WITH a AS (SELECT version, (SELECT max(scored_at) FROM risk_score WHERE model_version = m.version)
                        AS latest FROM risk_model m WHERE is_active)"""


async def load_plan(conn: Any, inspection_cost: float, days: int) -> tuple[dict[str, Any], list[Depot]]:
    """Builds the plan from this tenant's latest scores, depots and existing bookings (RLS-scoped)."""
    depots = [Depot(**r) for r in await (await conn.execute(
        "SELECT id, name, lat, lon, service_bays AS bays FROM depot ORDER BY id")).fetchall()]
    start = first_plan_day(datetime.now(UTC))
    booked_rows = await (await conn.execute("""
        SELECT depot_id, (scheduled_for AT TIME ZONE 'Asia/Kolkata')::date AS day, count(*) AS n
        FROM service_booking WHERE status = 'SCHEDULED' AND scheduled_for >= %s
        GROUP BY 1, 2""", (slot_time(start),))).fetchall()
    booked = {(r["depot_id"], r["day"]): r["n"] for r in booked_rows}
    # Only vehicles whose best case pays for an inspection, and none already booked.
    rows = await (await conn.execute(f"""{_LATEST}
        SELECT r.vehicle_id, trim(v.vin) AS vin, f.name AS fleet, f.home_depot_id, r.failure_prob_7d AS probability,
               r.predicted_component AS likely_part, r.est_cost_avoided_usd AS saving_usd
        FROM risk_score r JOIN vehicle v ON v.id = r.vehicle_id JOIN fleet f ON f.id = v.fleet_id
        WHERE (r.model_version, r.scored_at) = (SELECT version, latest FROM a)
          AND r.failure_prob_7d * r.est_cost_avoided_usd > %s AND f.home_depot_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM service_booking b WHERE b.vehicle_id = r.vehicle_id
                          AND b.status = 'SCHEDULED' AND b.scheduled_for >= now())""",  # noqa: S608  fixed fragment
        (inspection_cost,))).fetchall()
    candidates = [Candidate(**{**r, "probability": float(r["probability"]), "saving_usd": float(r["saving_usd"])})
                  for r in rows]
    p = plan(candidates, depots, booked, start, days, inspection_cost)
    return plan_summary(p, depots, booked), depots


class PlanConflict(Exception):
    pass


async def book_items(conn: Any, user: Principal, items: list[dict[str, Any]], source: str) -> list[int]:
    """Books plan items after re-checking them in this transaction: the vehicle and depot belong to
    the caller's tenant, the vehicle is not already booked, and the depot still has a free bay."""
    await ensure_user(conn, user)
    # One booking transaction per tenant at a time, so two approvals cannot both take the last bay.
    await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))", (f"bookings:{user.tenant_id}",))
    depots = {r["id"]: r for r in await (await conn.execute("SELECT id, service_bays FROM depot")).fetchall()}
    ids = [int(i["vehicle_id"]) for i in items]
    if len(set(ids)) != len(ids):
        raise PlanConflict("a vehicle appears twice")
    risk = {r["vehicle_id"]: r for r in await (await conn.execute(f"""{_LATEST}
        SELECT v.id AS vehicle_id, r.failure_prob_7d, r.predicted_component, r.est_cost_avoided_usd
        FROM vehicle v LEFT JOIN risk_score r ON r.vehicle_id = v.id
             AND (r.model_version, r.scored_at) = (SELECT version, latest FROM a)
        WHERE v.id = ANY(%s)""", (ids,))).fetchall()}   # noqa: S608  fixed fragment
    taken = {r["vehicle_id"] for r in await (await conn.execute(
        "SELECT vehicle_id FROM service_booking WHERE vehicle_id = ANY(%s) AND status = 'SCHEDULED' "
        "AND scheduled_for >= now()", (ids,))).fetchall()}
    load: dict[tuple[int, date], int] = {}
    for r in await (await conn.execute("""
            SELECT depot_id, (scheduled_for AT TIME ZONE 'Asia/Kolkata')::date AS day, count(*) AS n
            FROM service_booking WHERE status = 'SCHEDULED' AND scheduled_for >= now() GROUP BY 1, 2""")).fetchall():
        load[(r["depot_id"], r["day"])] = r["n"]

    rows = []
    for item in items:
        vid, did, day = int(item["vehicle_id"]), int(item["depot_id"]), date.fromisoformat(str(item["date"]))
        if vid not in risk:
            raise PlanConflict(f"vehicle {vid} is not in this fleet")
        if did not in depots:
            raise PlanConflict(f"depot {did} is not in this fleet")
        if vid in taken:
            raise PlanConflict(f"vehicle {vid} is already booked")
        if day < first_plan_day(datetime.now(UTC)):
            raise PlanConflict(f"{day} is in the past")
        load[(did, day)] = load.get((did, day), 0) + 1
        if load[(did, day)] > depots[did]["service_bays"]:
            raise PlanConflict(f"depot {did} has no free bay on {day}")
        r = risk[vid]
        reason = (f"Inspect {r['predicted_component'] or 'vehicle'}: {float(r['failure_prob_7d']):.0%} risk of a "
                  f"breakdown within 7 days, about ${float(r['est_cost_avoided_usd'] or 0):,.0f} avoided"
                  if r["failure_prob_7d"] is not None else "Planned inspection")
        rows.append((user.tenant_id, vid, did, slot_time(day), reason, user.user_id, source))
    booked = []
    for row in rows:
        booked.append((await (await conn.execute("""
            INSERT INTO service_booking (tenant_id, vehicle_id, depot_id, scheduled_for, reason, created_by, source)
            VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING id""", row)).fetchone())["id"])
    return booked


@router.get("/plan")
async def get_plan(request: Request, user: CurrentUser, _: RateLimited,
                   inspection_cost: Annotated[float, Query(ge=10, le=2000)] = DEFAULT_INSPECTION_COST,
                   days: Annotated[int, Query(ge=1, le=7)] = 3) -> dict[str, Any]:
    """Which vehicles to inspect, where and when, over the next few days."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        summary, _ = await load_plan(conn, inspection_cost, days)
    return summary


class PlanItem(BaseModel):
    vehicle_id: int
    depot_id: int
    date: date


class BookRequest(BaseModel):
    items: list[PlanItem] = Field(min_length=1, max_length=MAX_BOOKINGS_PER_REQUEST)


@router.post("/bookings")
async def book(body: BookRequest, request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    """Books the plan (or part of it) as shown. All or nothing: if anything changed, nothing is booked."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        try:
            ids = await book_items(conn, user, [i.model_dump() for i in body.items], "USER")
        except PlanConflict as e:
            raise ApiError(409, "Conflict", f"{e}; reload the plan") from None
        await audit.record(conn, request, user, "maintenance.book_plan", "service_booking", None,
                           {"bookings": len(ids), "first_id": ids[0], "last_id": ids[-1]})
    return {"booked": len(ids), "booking_ids": ids}


async def load_parts(conn: Any) -> dict[str, Any]:
    model = await (await conn.execute(
        "SELECT (metrics->'model_alert_threshold'->>'threshold')::float AS t FROM risk_model WHERE is_active"
    )).fetchone()
    rows = await (await conn.execute(f"""{_LATEST}
        SELECT d.id AS depot_id, d.name AS depot, r.predicted_component AS part,
               sum(r.failure_prob_7d) AS expected, sum(r.failure_prob_7d * (1 - r.failure_prob_7d)) AS variance,
               count(*) FILTER (WHERE r.failure_prob_7d >= %s) AS high_risk_vehicles
        FROM risk_score r JOIN vehicle v ON v.id = r.vehicle_id JOIN fleet f ON f.id = v.fleet_id
        JOIN depot d ON d.id = f.home_depot_id
        WHERE (r.model_version, r.scored_at) = (SELECT version, latest FROM a) AND r.predicted_component IS NOT NULL
        GROUP BY 1, 2, 3""", (model["t"] if model else 1.0,))).fetchall()   # noqa: S608  fixed fragment
    forecast = parts_forecast(rows)
    return {"horizon_days": 7, "expected_failures": round(sum(f["expected"] for f in forecast), 1),
            "items": forecast}


@router.get("/parts")
async def parts(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    """Expected failures in the next 7 days per depot and part, from the calibrated probabilities."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        return await load_parts(conn)


def plan_items_for_booking(summary: dict[str, Any]) -> list[dict[str, Any]]:
    """The compact form stored in a copilot proposal and replayed on approval."""
    return [{"vehicle_id": b["vehicle_id"], "vin": b["vin"], "depot_id": b["depot_id"], "depot": b["depot"],
             "date": b["date"], "probability": b["probability"], "likely_part": b["likely_part"]}
            for d in summary["depots"] for day in d["days"] for b in day["items"]]
