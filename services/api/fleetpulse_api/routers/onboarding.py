"""Onboarding a vehicle maker: propose a mapping, preview it against real parked traffic,
approve it. Approval publishes the spec to the compacted ``oem.mappings`` topic; every
gateway and normalizer picks it up within about a second, with no deploy, and the normalizer
replays the events that were parked while the maker had no mapping.

Mappings are platform configuration shared by every tenant, so changing them needs the
``platform_admin`` role (FleetPulse operations), which sees no customer fleet data.
"""

import json
import re
from typing import Annotated, Any

import httpx
from aiokafka import AIOKafkaProducer
from fastapi import APIRouter, Body, Request

from .. import audit
from ..auth import CurrentUser, PlatformAdmin
from ..db import ensure_user, tenant_tx
from ..errors import ApiError, not_found
from ..ratelimit import RateLimited

router = APIRouter(prefix="/v1/oem-mappings", tags=["onboarding"])

_OEM = re.compile(r"^[A-Z][A-Z0-9_]{1,29}$")
_REQUIRED_FIELDS = {"vin", "seq", "ts", "lat", "lon"}


def _validate(spec: Any) -> tuple[str, int]:
    """Shape checks before a draft is stored; the normalizer's preview compiles it for real."""
    if not isinstance(spec, dict):
        raise ApiError(422, "Invalid mapping", "the mapping must be a JSON object")
    oem = str(spec.get("oem", "")).upper()
    version = spec.get("version")
    fields = spec.get("fields")
    if not _OEM.match(oem):
        raise ApiError(422, "Invalid mapping", "oem must be 2-30 letters, digits or underscores")
    if not isinstance(version, int) or isinstance(version, bool) or version < 1:
        raise ApiError(422, "Invalid mapping", "version must be a positive integer")
    if not isinstance(fields, dict):
        raise ApiError(422, "Invalid mapping", "fields must be an object")
    missing = _REQUIRED_FIELDS - set(fields)
    if missing:
        raise ApiError(422, "Invalid mapping", f"fields is missing {', '.join(sorted(missing))}")
    return oem, version


_LIST_SQL = """
    SELECT m.id, m.oem_code, m.version, m.status, m.proposed_by, m.approved_by, u.email AS approved_by_email,
           m.created_at
    FROM oem_mapping m LEFT JOIN app_user u ON u.id = m.approved_by"""


@router.get("")
async def list_mappings(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        rows = await (await conn.execute(_LIST_SQL + " ORDER BY m.oem_code, m.version DESC")).fetchall()
    return {"items": rows}


@router.get("/status")
async def onboarding_status(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    """What the running pipeline has active, and the progress of any parked-event replay."""
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            resp = await client.get(f"{request.app.state.settings.normalizer_url}/status")
        resp.raise_for_status()
    except httpx.HTTPError:
        raise ApiError(503, "Normalizer unavailable", "cannot reach the normalizer") from None
    body = resp.json()
    return {"active_versions": body.get("mappings", {}), "replays": body.get("replays", {})}


@router.get("/drift")
async def feed_drift(request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    """The latest feed-drift check: each maker's recent readings against its own history, per field.
    Describes the makers' feeds, not any tenant's data, so every signed-in user may read it."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        rows = await (await conn.execute("""
            SELECT checked_at, oem_code, field, psi, baseline_mean, current_mean, current_n, status, hint
            FROM feed_drift WHERE checked_at = (SELECT max(checked_at) FROM feed_drift)
            ORDER BY oem_code, field""")).fetchall()
        # A drift that started recently is worth showing even if the latest check has calmed down.
        recent = await (await conn.execute("""
            SELECT oem_code, field, max(checked_at) AS last_seen, max(psi) AS worst_psi
            FROM feed_drift WHERE status = 'DRIFT' AND checked_at > now() - interval '1 hour'
            GROUP BY 1, 2""")).fetchall()
    return {"checked_at": rows[0]["checked_at"] if rows else None, "items": rows, "recent_drift": recent}


@router.get("/{mapping_id}")
async def get_mapping(mapping_id: int, request: Request, user: CurrentUser, _: RateLimited) -> dict[str, Any]:
    async with tenant_tx(request.app.state.pg, user) as conn:
        row = await (await conn.execute(_LIST_SQL.replace("m.created_at", "m.created_at, m.spec")
                                        + " WHERE m.id = %s", (mapping_id,))).fetchone()
    if row is None:
        raise not_found("mapping")
    return row


@router.post("", status_code=201)
async def propose(request: Request, user: PlatformAdmin, _: RateLimited,
                  spec: Annotated[Any, Body(description="the maker's mapping spec (JSON)")]) -> dict[str, Any]:
    oem, version = _validate(spec)
    spec["oem"] = oem
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        active = await (await conn.execute(
            "SELECT max(version) AS v FROM oem_mapping WHERE oem_code = %s AND status = 'ACTIVE'", (oem,))).fetchone()
        if active["v"] is not None and version <= active["v"]:
            raise ApiError(409, "Conflict", f"{oem} v{active['v']} is active; propose a higher version")
        row = await (await conn.execute(
            """INSERT INTO oem_mapping (oem_code, version, spec, status, proposed_by)
               VALUES (%s, %s, %s::jsonb, 'DRAFT', %s)
               ON CONFLICT (oem_code, version) DO NOTHING RETURNING id""",
            (oem, version, json.dumps(spec), user.user_id))).fetchone()
        if row is None:
            raise ApiError(409, "Conflict", f"{oem} v{version} already exists")
        await audit.record(conn, request, user, "mapping.propose", "oem_mapping", str(row["id"]),
                           {"oem": oem, "version": version})
    return {"id": row["id"], "oem_code": oem, "version": version, "status": "DRAFT"}


@router.post("/{mapping_id}/preview")
async def preview(mapping_id: int, request: Request, user: PlatformAdmin, _: RateLimited) -> dict[str, Any]:
    """Compiles the draft with the production engine and maps the maker's parked events."""
    async with tenant_tx(request.app.state.pg, user) as conn:
        row = await (await conn.execute("SELECT spec FROM oem_mapping WHERE id = %s", (mapping_id,))).fetchone()
    if row is None:
        raise not_found("mapping")
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(f"{request.app.state.settings.normalizer_url}/mappings/preview",
                                     content=json.dumps(row["spec"]), headers={"Content-Type": "application/json"})
        resp.raise_for_status()
    except httpx.HTTPError:
        raise ApiError(503, "Normalizer unavailable", "cannot reach the normalizer to preview") from None
    return resp.json()


@router.post("/{mapping_id}/approve")
async def approve(mapping_id: int, request: Request, user: PlatformAdmin, _: RateLimited) -> dict[str, Any]:
    """Activates the draft. The database change and the publish succeed or fail together:
    the publish happens inside the transaction and must be acknowledged by the brokers
    before the commit, so Postgres never says ACTIVE for a mapping the pipeline did not get.
    (Re-approving after a failed commit is harmless: the registry ignores a version it has.)"""
    settings = request.app.state.settings
    async with tenant_tx(request.app.state.pg, user) as conn:
        await ensure_user(conn, user)
        row = await (await conn.execute(
            "SELECT id, oem_code, version, spec, status FROM oem_mapping WHERE id = %s FOR UPDATE",
            (mapping_id,))).fetchone()
        if row is None:
            raise not_found("mapping")
        if row["status"] != "DRAFT":
            raise ApiError(409, "Conflict", f"mapping is {row['status']}, only a DRAFT can be approved")
        retired = await (await conn.execute(
            "UPDATE oem_mapping SET status = 'RETIRED' WHERE oem_code = %s AND status = 'ACTIVE' RETURNING version",
            (row["oem_code"],))).fetchall()
        await conn.execute("UPDATE oem_mapping SET status = 'ACTIVE', approved_by = %s WHERE id = %s",
                           (user.user_id, mapping_id))
        producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap, acks="all",
                                    enable_idempotence=True, request_timeout_ms=10_000)
        try:
            await producer.start()
            await producer.send_and_wait("oem.mappings", key=row["oem_code"].encode(),
                                         value=json.dumps(row["spec"]).encode())
        except Exception as e:
            raise ApiError(503, "Publish failed", f"could not publish the mapping; nothing changed ({e})") from None
        finally:
            await producer.stop()
        await audit.record(conn, request, user, "mapping.approve", "oem_mapping", str(mapping_id),
                           {"oem": row["oem_code"], "version": row["version"],
                            "retired": [r["version"] for r in retired]})
    return {"id": mapping_id, "oem_code": row["oem_code"], "version": row["version"], "status": "ACTIVE"}

