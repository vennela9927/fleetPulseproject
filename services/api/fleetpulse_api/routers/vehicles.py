from datetime import UTC, datetime, timedelta
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Query, Request

from ..auth import CurrentUser
from ..db import tenant_tx
from ..errors import ApiError, not_found
from ..pagination import MAX_LIMIT, decode_cursor, page
from ..ratelimit import RateLimited, rate_limit

router = APIRouter(prefix="/v1", tags=["vehicles"])

_VEHICLE_COLUMNS = """
    v.id, v.vin, v.model_year, v.status, v.fleet_id, f.name AS fleet_name,
    m.name AS model, m.powertrain, o.name AS oem"""
_VEHICLE_FROM = """
    FROM vehicle v
    JOIN fleet f ON f.id = v.fleet_id
    JOIN vehicle_model m ON m.id = v.model_id
    JOIN oem o ON o.id = m.oem_id"""


@router.get("/fleets")
async def list_fleets(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        cur = await conn.execute("""
            SELECT f.id, f.name, d.name AS home_depot, count(v.id) AS vehicles
            FROM fleet f
            LEFT JOIN depot d ON d.id = f.home_depot_id
            LEFT JOIN vehicle v ON v.fleet_id = f.id
            GROUP BY f.id, d.name ORDER BY f.name""")
        return {"items": await cur.fetchall()}


@router.get("/vehicles")
async def list_vehicles(
    request: Request, user: CurrentUser, _: RateLimited,
    fleet_id: int | None = None,
    powertrain: Literal["ICE", "HYBRID", "EV"] | None = None,
    status: Literal["ACTIVE", "IN_SERVICE", "RETIRED"] | None = None,
    vin_prefix: Annotated[str | None, Query(min_length=3, max_length=17, pattern="^[A-HJ-NPR-Z0-9]+$")] = None,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = 100,
    cursor: str | None = None,
) -> dict[str, Any]:
    after = decode_cursor(cursor, {"id": int})
    where, params = [], []
    if fleet_id is not None:
        where.append("v.fleet_id = %s")
        params.append(fleet_id)
    if powertrain:
        where.append("m.powertrain = %s")
        params.append(powertrain)
    if status:
        where.append("v.status = %s")
        params.append(status)
    if vin_prefix:
        where.append("v.vin LIKE %s")
        params.append(vin_prefix + "%")
    if after:
        where.append("v.id > %s")
        params.append(after["id"])
    sql = f"SELECT {_VEHICLE_COLUMNS} {_VEHICLE_FROM}"
    if where:
        sql += " WHERE " + " AND ".join(where)   # fixed fragments only; values are bound
    sql += " ORDER BY v.id LIMIT %s"
    params.append(limit + 1)
    async with tenant_tx(request.app.state.pg, user) as conn:
        rows = await (await conn.execute(sql, params)).fetchall()   # type: ignore[arg-type]
    return page(rows, limit, lambda r: {"id": r["id"]})


@router.get("/vehicles/{vehicle_id}")
async def get_vehicle(vehicle_id: int, request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        vehicle = await (await conn.execute(
            f"SELECT {_VEHICLE_COLUMNS}, v.registered_at {_VEHICLE_FROM} WHERE v.id = %s", (vehicle_id,))).fetchone()
        if vehicle is None:
            raise not_found("vehicle")
        alerts = await (await conn.execute("""
            SELECT a.id, a.rule_code, r.severity, a.status, a.opened_at, a.details
            FROM alert a JOIN alert_rule r ON r.code = a.rule_code
            WHERE a.vehicle_id = %s AND a.status <> 'RESOLVED'
            ORDER BY a.opened_at DESC LIMIT 20""", (vehicle_id,))).fetchall()
        risk = await (await conn.execute("""
            SELECT scored_at, model_version, failure_prob_7d, predicted_component,
                   est_cost_avoided_usd, top_factors
            FROM risk_score WHERE vehicle_id = %s ORDER BY scored_at DESC LIMIT 1""", (vehicle_id,))).fetchone()

    live = await request.app.state.redis.hgetall(f"v:{vehicle['vin'].strip()}")
    # Live state is keyed by VIN, not tenant: check ownership before showing it.
    if live.get("tenant") != user.tenant_id:
        live = {}
    return {**vehicle, "vin": vehicle["vin"].strip(), "live": _live(live), "open_alerts": alerts, "risk": risk}


def _live(h: dict[str, str]) -> dict[str, Any] | None:
    if not h:
        return None

    def num(k: str) -> float | None:
        return float(h[k]) if h.get(k) else None

    return {
        "ts": datetime.fromtimestamp(int(h["ts"]) / 1000, UTC).isoformat(),
        "status": h.get("status"), "lat": num("lat"), "lon": num("lon"),
        "speed_kmh": num("speed_kmh"), "odo_km": num("odo_km"), "fuel_pct": num("fuel_pct"),
        "soc_pct": num("soc_pct"), "coolant_c": num("coolant_c"), "batt_v": num("batt_v"),
        "dtc": [c for c in h.get("dtc", "").split(",") if c], "evt": h.get("evt"),
    }


_MAX_SPAN = {"raw": timedelta(hours=24), "1m": timedelta(days=7), "1d": timedelta(days=90)}

_TELEMETRY_SQL = {
    "raw": """
        SELECT ts, lat, lon, speed_kmh, odo_km, engine_on, rpm, fuel_pct, soc_pct, coolant_c, batt_v, dtc, evt
        FROM fleet.telemetry FINAL
        WHERE vin = {vin:FixedString(17)} AND ts >= {start:DateTime64(3)} AND ts < {end:DateTime64(3)}
        ORDER BY ts LIMIT {limit:UInt32}""",
    "1m": """
        SELECT minute AS ts, countMerge(samples) AS samples, round(avgMerge(avg_speed), 1) AS avg_speed_kmh,
               maxMerge(max_coolant) AS max_coolant_c, round(minMerge(min_batt_v), 2) AS min_batt_v,
               argMaxMerge(last_lat) AS lat, argMaxMerge(last_lon) AS lon
        FROM fleet.telemetry_1m
        WHERE vin = {vin:FixedString(17)} AND minute >= {start:DateTime64(3)} AND minute < {end:DateTime64(3)}
        GROUP BY minute ORDER BY minute LIMIT {limit:UInt32}""",
    "1d": """
        SELECT day AS ts, countMerge(samples) AS samples,
               round(maxMerge(km_end) - minMerge(km_start), 1) AS km,
               round(avgMerge(coolant_avg), 1) AS coolant_avg_c, maxMerge(coolant_max) AS coolant_max_c,
               round(minMerge(batt_v_min), 2) AS batt_v_min, minMerge(soh_min) AS soh_min_pct,
               sumMerge(dtc_events) AS dtc_events, groupUniqArrayArrayMerge(dtc_distinct) AS dtc_codes,
               countIfMerge(harsh_events) AS harsh_events, countIfMerge(idle_samples) AS idle_samples
        FROM fleet.vehicle_daily
        WHERE vin = {vin:FixedString(17)} AND day >= toDate({start:DateTime64(3)}) AND day < toDate({end:DateTime64(3)})
        GROUP BY day ORDER BY day LIMIT {limit:UInt32}""",
}


@router.get("/vehicles/{vehicle_id}/telemetry")
async def vehicle_telemetry(
    vehicle_id: int, request: Request, user: CurrentUser,
    _: Annotated[None, rate_limit(cost=5)],
    resolution: Literal["raw", "1m", "1d"] = "raw",
    start: datetime | None = None,
    end: datetime | None = None,
    limit: Annotated[int, Query(ge=1, le=20_000)] = 5_000,
) -> dict[str, Any]:
    end = _utc(end) if end else datetime.now(UTC)
    start = _utc(start) if start else end - (timedelta(hours=1) if resolution == "raw" else _MAX_SPAN[resolution])
    if start >= end:
        raise ApiError(422, "Invalid request", "start must be before end")
    if end - start > _MAX_SPAN[resolution]:
        raise ApiError(422, "Invalid request",
                       f"at most {_MAX_SPAN[resolution]} per request at resolution {resolution}")

    # Postgres (RLS) decides whether the caller may see this vehicle at all.
    async with tenant_tx(request.app.state.pg, user) as conn:
        row = await (await conn.execute("SELECT vin FROM vehicle WHERE id = %s", (vehicle_id,))).fetchone()
    if row is None:
        raise not_found("vehicle")
    rows = await request.app.state.clickhouse.query(user, _TELEMETRY_SQL[resolution], {
        "vin": row["vin"].strip(), "start": _ch_ts(start), "end": _ch_ts(end), "limit": limit})
    return {"vehicle_id": vehicle_id, "resolution": resolution, "start": start, "end": end,
            "items": rows, "truncated": len(rows) >= limit}


def _utc(t: datetime) -> datetime:
    return t.replace(tzinfo=UTC) if t.tzinfo is None else t.astimezone(UTC)


def _ch_ts(t: datetime) -> str:
    return t.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
