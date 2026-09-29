from typing import Any

from fastapi import APIRouter, Request

from ..auth import CurrentUser, Manager
from ..db import ensure_user, tenant_tx
from ..ratelimit import RateLimited

router = APIRouter(prefix="/v1", tags=["account"])


@router.get("/me")
async def me(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        tenant = await (await conn.execute("SELECT name FROM tenant WHERE id = %s", (user.tenant_id,))).fetchone()
    return {"user_id": user.user_id, "username": user.username, "email": user.email,
            "tenant_id": user.tenant_id, "tenant_name": tenant["name"] if tenant else None,
            "roles": sorted(user.roles)}


@router.get("/audit/verify")
async def verify_audit_chain(request: Request, user: Manager, _: RateLimited) -> dict[str, Any]:
    """Recomputes the audit log's hash chain. Any edited or deleted row breaks it at that row."""
    async with tenant_tx(request.app.state.pg, user, statement_timeout_ms=30_000) as conn:
        row = await (await conn.execute("SELECT audit_verify_chain() AS first_bad_id")).fetchone()
        rows = (await (await conn.execute("SELECT count(*) AS n FROM audit_log")).fetchone())["n"]
    return {"intact": row["first_bad_id"] is None, "first_bad_id": row["first_bad_id"],
            "rows_visible_to_tenant": rows}
