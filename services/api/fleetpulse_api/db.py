"""Postgres access, always inside a tenant-scoped transaction.

The API connects as ``fleet_app``, which is subject to row-level security. Every request
runs in a transaction that first sets ``app.tenant_id`` from the verified token with
``set_config(..., is_local => true)``: the setting ends with the transaction, so a pooled
connection can never carry one tenant's context into another tenant's request.
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from psycopg import AsyncConnection
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from .auth import Principal
from .config import Settings


def create_pool(settings: Settings) -> AsyncConnectionPool:
    return AsyncConnectionPool(settings.postgres_dsn, min_size=2, max_size=settings.postgres_pool_max,
                               kwargs={"row_factory": dict_row, "application_name": "fleetpulse-api"},
                               open=False)


@asynccontextmanager
async def tenant_tx(pool: AsyncConnectionPool, user: Principal,
                    statement_timeout_ms: int = 5000) -> AsyncIterator[AsyncConnection]:
    async with pool.connection() as conn, conn.transaction():
        await conn.execute(
            "SELECT set_config('app.tenant_id', %s, true), set_config('statement_timeout', %s, true)",
            (user.tenant_id, str(statement_timeout_ms)))
        yield conn


async def ensure_user(conn: AsyncConnection, user: Principal) -> None:
    """Just-in-time provisioning: the first write by a Keycloak user creates their app_user row,
    which alerts, bookings and approvals reference. RLS's WITH CHECK only admits the caller's
    own tenant."""
    await conn.execute(
        "INSERT INTO app_user (id, tenant_id, email) VALUES (%s, %s, %s) ON CONFLICT DO NOTHING",
        (user.user_id, user.tenant_id, user.email or f"{user.user_id}@users.invalid"))
