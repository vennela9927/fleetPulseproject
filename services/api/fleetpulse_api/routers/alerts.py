import asyncio
from collections.abc import AsyncIterator
from datetime import datetime
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Query, Request
from fastapi.responses import StreamingResponse

from .. import audit
from ..auth import CurrentUser, Manager
from ..db import ensure_user, tenant_tx
from ..errors import ApiError, not_found
from ..pagination import MAX_LIMIT, decode_cursor, page
from ..ratelimit import RateLimited

router = APIRouter(prefix="/v1/alerts", tags=["alerts"])

Status = Literal["OPEN", "ACKNOWLEDGED", "RESOLVED"]


@router.get("")
async def list_alerts(
    request: Request, user: CurrentUser, _: RateLimited,
    status: Status | None = None,
    severity: Literal["INFO", "WARNING", "CRITICAL"] | None = None,
    rule: Annotated[str | None, Query(pattern="^[A-Z0-9_]{3,40}$")] = None,
    vehicle_id: int | None = None,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = 50,
    cursor: str | None = None,
) -> dict[str, Any]:
    after = decode_cursor(cursor, {"opened_at": str, "id": int})
    where, params = [], []
    for clause, value in (("a.status = %s", status), ("r.severity = %s", severity),
                          ("a.rule_code = %s", rule), ("a.vehicle_id = %s", vehicle_id)):
        if value is not None:
            where.append(clause)
            params.append(value)
    if after:
        try:
            opened = datetime.fromisoformat(after["opened_at"])
        except ValueError:
            raise ApiError(400, "Invalid cursor", "cursor is not one this API issued") from None
        # Newest first; (opened_at, id) is unique, so no alert is skipped or repeated.
        where.append("(a.opened_at, a.id) < (%s, %s)")
        params += [opened, after["id"]]
    sql = """
        SELECT a.id, a.vehicle_id, trim(v.vin) AS vin, a.rule_code, r.severity, a.status,
               a.opened_at, a.detected_at, a.acknowledged_by, a.resolved_at, a.details
        FROM alert a
        JOIN alert_rule r ON r.code = a.rule_code
        JOIN vehicle v ON v.id = a.vehicle_id"""
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY a.opened_at DESC, a.id DESC LIMIT %s"
    params.append(limit + 1)
    async with tenant_tx(request.app.state.pg, user) as conn:
        rows = await (await conn.execute(sql, params)).fetchall()   # type: ignore[arg-type]
    return page(rows, limit, lambda r: {"opened_at": r["opened_at"].isoformat(), "id": r["id"]})


async def _transition(request: Request, user: CurrentUser, alert_id: int, to: Status,
                      allowed_from: tuple[str, ...], set_sql: str) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        # The status condition makes the transition atomic: two managers acting at once
        # cannot both acknowledge the same alert.
        row = await (await conn.execute(
            f"UPDATE alert SET status = %s, {set_sql} WHERE id = %s AND status = ANY(%s) "  # noqa: S608  fixed fragment
            "RETURNING id, status, acknowledged_by, resolved_at",
            (to, user.user_id, alert_id, list(allowed_from)))).fetchone()
        if row is None:
            current = await (await conn.execute("SELECT status FROM alert WHERE id = %s", (alert_id,))).fetchone()
            if current is None:
                raise not_found("alert")
            raise ApiError(409, "Conflict", f"alert is {current['status']}, cannot move to {to}")
        await audit.record(conn, request, user, f"alert.{to.lower()}", "alert", str(alert_id),
                           {"to": to})
        return row


@router.post("/{alert_id}/acknowledge")
async def acknowledge(alert_id: int, request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    return await _transition(request, user, alert_id, "ACKNOWLEDGED", ("OPEN",), "acknowledged_by = %s")


@router.post("/{alert_id}/resolve")
async def resolve(alert_id: int, request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    return await _transition(request, user, alert_id, "RESOLVED", ("OPEN", "ACKNOWLEDGED"),
                             "acknowledged_by = coalesce(acknowledged_by, %s), resolved_at = now()")


@router.get("/stream")
async def stream(request: Request, user: CurrentUser, _: RateLimited) -> StreamingResponse:
    """New alerts for the caller's tenant as Server-Sent Events. The channel name comes from
    the verified token, so a client cannot subscribe to another tenant's feed."""

    async def events() -> AsyncIterator[str]:
        pubsub = request.app.state.redis.pubsub()
        await pubsub.subscribe(f"alerts:{user.tenant_id}")
        try:
            yield ": connected\n\n"
            while not await request.is_disconnected():
                msg = await pubsub.get_message(ignore_subscribe_messages=True, timeout=15)
                if msg is None:
                    yield ": keep-alive\n\n"   # stops proxies closing an idle connection
                    continue
                yield f"event: alert\ndata: {msg['data']}\n\n"
        except asyncio.CancelledError:
            pass
        finally:
            await pubsub.unsubscribe()
            await pubsub.aclose()

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})
