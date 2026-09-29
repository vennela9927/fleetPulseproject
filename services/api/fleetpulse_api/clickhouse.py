"""Read-only ClickHouse queries as ``fleet_api``, whose row policies filter every telemetry
table by the ``SQL_tenant_id`` setting. Each query carries the caller's tenant, so even a
query that forgets its own tenant filter cannot return another tenant's rows, and a query
sent without the setting fails rather than returning everything."""

from typing import Any

import httpx

from .auth import Principal
from .config import Settings
from .errors import ApiError


class ClickHouse:
    def __init__(self, settings: Settings) -> None:
        self._client = httpx.AsyncClient(base_url=settings.clickhouse_url, timeout=20,
                                         auth=(settings.clickhouse_user, settings.clickhouse_password))

    async def query(self, user: Principal, sql: str, params: dict[str, Any]) -> list[dict[str, Any]]:
        """Runs ``sql`` with ClickHouse server-side parameters (``{name:Type}``), never string
        interpolation, and returns rows as dicts."""
        query_params = {f"param_{k}": str(v) for k, v in params.items()}
        query_params["SQL_tenant_id"] = user.tenant_id
        query_params["default_format"] = "JSON"
        query_params["output_format_json_quote_64bit_integers"] = "0"
        resp = await self._client.post("/", params=query_params, content=sql.encode())
        if resp.status_code != 200:
            raise ApiError(502, "Telemetry store error", resp.text.splitlines()[0][:200] if resp.text else None)
        return resp.json()["data"]

    async def ping(self) -> bool:
        try:
            return (await self._client.get("/ping")).status_code == 200
        except httpx.HTTPError:
            return False

    async def close(self) -> None:
        await self._client.aclose()
