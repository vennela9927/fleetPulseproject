"""Writes to the hash-chained, append-only audit log (see infra/postgres/init/02_security.sh)."""

import json
from typing import Any

from fastapi import Request
from psycopg import AsyncConnection

from .auth import Principal


async def record(conn: AsyncConnection, request: Request, user: Principal, action: str,
                 resource_type: str, resource_id: str | None, details: dict[str, Any] | None = None,
                 purpose: str | None = None) -> None:
    """Appends an audit row in the caller's transaction, so the action and its record
    commit or roll back together."""
    await conn.execute(
        """INSERT INTO audit_log (tenant_id, actor_id, actor_type, action, resource_type, resource_id,
                                  purpose, request_id, details)
           VALUES (%s, %s, 'USER', %s, %s, %s, %s, %s, %s::jsonb)""",
        (user.tenant_id, user.user_id, action, resource_type, resource_id, purpose,
         getattr(request.state, "request_id", None), json.dumps(details or {})))
