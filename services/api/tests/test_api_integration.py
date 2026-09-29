"""End-to-end against the running stack with real Keycloak tokens.

The core property is tenant isolation: whatever a caller asks for, it only ever gets its own
tenant's data, and another tenant's resource looks exactly like one that does not exist.
"""

import uuid
from datetime import UTC, datetime, timedelta

import httpx
import psycopg
import pytest
from redis.asyncio import Redis

from fleetpulse_api.config import Settings
from fleetpulse_api.main import create_app

from .conftest import ACME, ZENITH, bearer

pytestmark = pytest.mark.integration


async def first_vehicle(client, token) -> dict:
    resp = await client.get("/v1/vehicles?limit=1", headers=bearer(token))
    assert resp.status_code == 200
    return resp.json()["items"][0]


async def test_each_caller_is_resolved_to_their_own_tenant(client, tokens):
    for name, tenant in (("acme", ACME), ("zenith", ZENITH)):
        me = (await client.get("/v1/me", headers=bearer(tokens[name]))).json()
        assert me["tenant_id"] == tenant


async def test_another_tenants_vehicle_looks_like_a_missing_one(client, tokens):
    zenith_vehicle = await first_vehicle(client, tokens["zenith"])
    as_acme = await client.get(f"/v1/vehicles/{zenith_vehicle['id']}", headers=bearer(tokens["acme"]))
    missing = await client.get("/v1/vehicles/999999999", headers=bearer(tokens["acme"]))
    assert as_acme.status_code == missing.status_code == 404
    assert as_acme.json()["detail"] == missing.json()["detail"]

    as_owner = await client.get(f"/v1/vehicles/{zenith_vehicle['id']}", headers=bearer(tokens["zenith"]))
    assert as_owner.status_code == 200


async def test_another_tenants_telemetry_is_not_reachable(client, tokens):
    zenith_vehicle = await first_vehicle(client, tokens["zenith"])
    resp = await client.get(f"/v1/vehicles/{zenith_vehicle['id']}/telemetry?resolution=1d",
                            headers=bearer(tokens["acme"]))
    assert resp.status_code == 404


async def test_daily_history_comes_from_the_backfill(client, tokens):
    v = await first_vehicle(client, tokens["acme"])
    resp = await client.get(f"/v1/vehicles/{v['id']}/telemetry?resolution=1d", headers=bearer(tokens["acme"]))
    assert resp.status_code == 200
    days = resp.json()["items"]
    assert len(days) >= 10
    assert all(d["samples"] > 0 for d in days)


async def test_listing_only_ever_contains_the_callers_vehicles(client, tokens):
    async def ids(who: str) -> set[int]:
        resp = await client.get("/v1/vehicles?limit=500", headers=bearer(tokens[who]))
        return {v["id"] for v in resp.json()["items"]}

    acme_ids, zenith_ids = await ids("acme"), await ids("zenith")
    assert acme_ids and zenith_ids and not acme_ids & zenith_ids


async def test_pagination_walks_forward_without_gaps_or_repeats(client, tokens):
    seen, cursor = [], None
    for _ in range(3):
        url = "/v1/vehicles?limit=200" + (f"&cursor={cursor}" if cursor else "")
        body = (await client.get(url, headers=bearer(tokens["acme"]))).json()
        seen += [v["id"] for v in body["items"]]
        cursor = body["next_cursor"]
    assert len(seen) == 600 == len(set(seen))
    assert seen == sorted(seen)


async def test_page_size_is_capped(client, tokens):
    resp = await client.get("/v1/vehicles?limit=501", headers=bearer(tokens["acme"]))
    assert resp.status_code == 422


async def test_a_tampered_cursor_is_a_client_error(client, tokens):
    resp = await client.get("/v1/vehicles?cursor=eyJpZCI6IngifQ", headers=bearer(tokens["acme"]))
    assert resp.status_code == 400
    assert resp.headers["content-type"].startswith("application/problem+json")


async def test_filters_compose_with_tenant_isolation(client, tokens):
    body = (await client.get("/v1/vehicles?powertrain=EV&limit=50", headers=bearer(tokens["acme"]))).json()
    assert body["items"] and all(v["powertrain"] == "EV" for v in body["items"])


async def test_live_map_only_shows_the_callers_vehicles(client, tokens):
    redis = Redis.from_url(Settings().redis_url, decode_responses=True)
    try:
        for name, tenant in (("acme", ACME), ("zenith", ZENITH)):
            resp = await client.get("/v1/live/vehicles?limit=2000", headers=bearer(tokens[name]))
            assert resp.status_code == 200
            body = resp.json()
            assert body["fields"][0] == "vin"
            vins = [r[0] for r in body["rows"]]
            if not vins:
                pytest.skip("no live state in Redis; run the pipeline first")
            owners = await redis.hmget(f"v:{vins[0]}", "tenant")
            assert owners == [tenant]
            pipe = redis.pipeline()
            for vin in vins:
                pipe.hget(f"v:{vin}", "tenant")
            assert set(await pipe.execute()) == {tenant}
    finally:
        await redis.aclose()


async def test_a_bad_bounding_box_is_rejected(client, tokens):
    resp = await client.get("/v1/live/vehicles?bbox=80,20,70,10", headers=bearer(tokens["acme"]))
    assert resp.status_code == 422


# --------------------------------------------------------------------------- alerts

async def make_alert(tenant: str, vehicle_id: int) -> int:
    """Inserts an alert as fleet_app under the tenant's RLS context, like the product would."""
    async with await psycopg.AsyncConnection.connect(Settings().postgres_dsn) as conn, conn.transaction():
        await conn.execute("SELECT set_config('app.tenant_id', %s, true)", (tenant,))
        cur = await conn.execute(
            "INSERT INTO alert (tenant_id, vehicle_id, rule_code, dedup_key, opened_at, details) "
            "VALUES (%s, %s, 'HARSH_DRIVING', %s, now(), '{\"test\": true}') RETURNING id",
            (tenant, vehicle_id, f"test:{uuid.uuid4()}"))
        return (await cur.fetchone())[0]


async def test_alert_lifecycle_with_roles_conflicts_and_audit(client, tokens):
    v = await first_vehicle(client, tokens["acme"])
    alert_id = await make_alert(ACME, v["id"])

    viewer = await client.post(f"/v1/alerts/{alert_id}/acknowledge", headers=bearer(tokens["acme_viewer"]))
    assert viewer.status_code == 403

    other_tenant = await client.post(f"/v1/alerts/{alert_id}/acknowledge", headers=bearer(tokens["zenith"]))
    assert other_tenant.status_code == 404

    ack = await client.post(f"/v1/alerts/{alert_id}/acknowledge", headers=bearer(tokens["acme"]))
    assert ack.status_code == 200
    assert ack.json()["status"] == "ACKNOWLEDGED"

    again = await client.post(f"/v1/alerts/{alert_id}/acknowledge", headers=bearer(tokens["acme"]))
    assert again.status_code == 409

    resolved = await client.post(f"/v1/alerts/{alert_id}/resolve", headers=bearer(tokens["acme"]))
    assert resolved.status_code == 200
    assert resolved.json()["resolved_at"] is not None

    verify = (await client.get("/v1/audit/verify", headers=bearer(tokens["acme"]))).json()
    assert verify["intact"] is True
    assert verify["rows_visible_to_tenant"] >= 2


async def test_alert_listing_is_newest_first_and_paginates(client, tokens):
    v = await first_vehicle(client, tokens["zenith"])
    for _ in range(3):
        await make_alert(ZENITH, v["id"])
    first = (await client.get("/v1/alerts?limit=2", headers=bearer(tokens["zenith"]))).json()
    second = (await client.get(f"/v1/alerts?limit=2&cursor={first['next_cursor']}",
                               headers=bearer(tokens["zenith"]))).json()
    items = first["items"] + second["items"]
    keys = [(a["opened_at"], a["id"]) for a in items]
    assert keys == sorted(keys, reverse=True)
    assert len({a["id"] for a in items}) == 4

    acme_view = (await client.get(f"/v1/alerts?vehicle_id={v['id']}", headers=bearer(tokens["acme"]))).json()
    assert acme_view["items"] == []


# --------------------------------------------------------------------------- limits and errors

async def test_rate_limit_answers_429_with_retry_after(tokens):
    app = create_app(Settings(rate_limit_per_second=1, rate_limit_burst=3))
    redis = Redis.from_url(Settings().redis_url, decode_responses=True)
    await redis.delete("rl:0a000000-0000-4000-8000-000000000002")
    async with app.router.lifespan_context(app), httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://api") as c:
        codes = [(await c.get("/v1/me", headers=bearer(tokens["acme_viewer"]))).status_code for _ in range(5)]
        limited = await c.get("/v1/me", headers=bearer(tokens["acme_viewer"]))
    await redis.delete("rl:0a000000-0000-4000-8000-000000000002")
    await redis.aclose()
    assert codes[:3] == [200, 200, 200]
    assert 429 in codes[3:]
    assert limited.status_code == 429
    assert int(limited.headers["Retry-After"]) >= 1


async def test_errors_are_problem_json_with_a_request_id(client):
    resp = await client.get("/v1/vehicles", headers={"X-Request-ID": "trace-123"})
    assert resp.status_code == 401
    assert resp.headers["X-Request-ID"] == "trace-123"
    body = resp.json()
    assert body["status"] == 401 and body["request_id"] == "trace-123"


async def test_telemetry_window_is_bounded(client, tokens):
    v = await first_vehicle(client, tokens["acme"])
    end = datetime.now(UTC)
    start = end - timedelta(days=3)
    resp = await client.get(f"/v1/vehicles/{v['id']}/telemetry",
                            params={"resolution": "raw", "start": start.isoformat(), "end": end.isoformat()},
                            headers=bearer(tokens["acme"]))
    assert resp.status_code == 422
