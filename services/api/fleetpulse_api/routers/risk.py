from typing import Annotated, Any

from fastapi import APIRouter, Query, Request

from ..auth import CurrentUser
from ..db import tenant_tx
from ..errors import ApiError
from ..pagination import MAX_LIMIT, decode_cursor, page
from ..ratelimit import RateLimited

router = APIRouter(prefix="/v1/risk", tags=["risk"])


@router.get("/model")
async def active_model(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    """The active failure model and how it did on held-out vehicles and days, against the rule baseline."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        row = await (await conn.execute(
            "SELECT version, trained_at, metrics FROM risk_model WHERE is_active")).fetchone()
    if row is None:
        raise ApiError(404, "Not found", "no failure model has been trained yet")
    return row


@router.get("")
async def at_risk(
    request: Request, user: CurrentUser, _: RateLimited,
    min_probability: Annotated[float, Query(ge=0, le=1)] = 0.0,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = 50,
    cursor: str | None = None,
) -> dict[str, Any]:
    """The caller's vehicles by 7-day breakdown risk, highest first, from the latest scoring run."""
    after = decode_cursor(cursor, {"p": float, "id": int})
    where = ["(r.model_version, r.scored_at) = (SELECT version, latest FROM a)", "r.failure_prob_7d >= %s"]
    params: list[Any] = [min_probability]
    if after:
        where.append("(r.failure_prob_7d, -r.vehicle_id) < (%s, %s)")
        params += [after["p"], -after["id"]]
    params.append(limit + 1)
    async with tenant_tx(request.app.state.pg, user) as conn:
        rows = await (await conn.execute(f"""
            WITH a AS (   -- scalars, so risk_score_ranked serves rows already in rank order
                SELECT version, (SELECT max(scored_at) FROM risk_score WHERE model_version = m.version) AS latest
                FROM risk_model m WHERE is_active)
            SELECT r.vehicle_id, trim(v.vin) AS vin, f.name AS fleet_name, o.name AS oem, vm.name AS model,
                   r.failure_prob_7d, r.predicted_component, r.est_cost_avoided_usd, r.top_factors, r.scored_at
            FROM risk_score r
            JOIN vehicle v ON v.id = r.vehicle_id
            JOIN fleet f ON f.id = v.fleet_id
            JOIN vehicle_model vm ON vm.id = v.model_id
            JOIN oem o ON o.id = vm.oem_id
            WHERE {" AND ".join(where)}
            ORDER BY r.failure_prob_7d DESC, r.vehicle_id
            LIMIT %s""", params)).fetchall()   # noqa: S608  fixed fragments; values are bound
    return page(rows, limit, lambda r: {"p": float(r["failure_prob_7d"]), "id": r["vehicle_id"]})
