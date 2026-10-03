import asyncio
import sys

import httpx
import pytest

# 127.0.0.1, not localhost: on Windows the IPv6 attempt first costs seconds. The issuer is pinned by KC_HOSTNAME.
KEYCLOAK_TOKEN_URL = "http://127.0.0.1:8080/realms/fleetpulse/protocol/openid-connect/token"
USERS = {
    "acme": ("acme.manager", "acme-demo-2026"),
    "acme_viewer": ("acme.viewer", "acme-view-2026"),
    "zenith": ("zenith.manager", "zenith-demo-2026"),
}
ACME = "11111111-1111-1111-1111-111111111111"
ZENITH = "22222222-2222-2222-2222-222222222222"


if sys.platform == "win32":
    # Async psycopg cannot use Windows' default Proactor loop. Defined only on Windows:
    # pytest-asyncio rejects a hook that returns None, and elsewhere the default loop is fine.
    def pytest_asyncio_loop_factories(config, item):
        return {"selector": asyncio.SelectorEventLoop}


def _stack_is_up() -> bool:
    try:
        return httpx.get("http://127.0.0.1:8080/realms/fleetpulse", timeout=20).status_code == 200
    except httpx.HTTPError:
        return False


def pytest_collection_modifyitems(config, items):
    if _stack_is_up():
        return
    skip = pytest.mark.skip(reason="docker compose stack (Keycloak, Postgres, Redis, ClickHouse) is not running")
    for item in items:
        if "integration" in item.keywords:
            item.add_marker(skip)


@pytest.fixture(scope="session")
def tokens() -> dict[str, str]:
    out = {}
    for name, (username, password) in USERS.items():
        resp = httpx.post(KEYCLOAK_TOKEN_URL, data={"grant_type": "password", "client_id": "fleetpulse-cli",
                                                    "username": username, "password": password}, timeout=30)
        resp.raise_for_status()
        out[name] = resp.json()["access_token"]
    return out


@pytest.fixture(scope="session")
async def app():
    from fleetpulse_api.main import create_app

    application = create_app()
    async with application.router.lifespan_context(application):
        yield application


@pytest.fixture
async def client(app):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://api") as c:
        yield c


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
