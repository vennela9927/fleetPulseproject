"""API latency under steady load: p50 / p95 / p99 per endpoint.

Three users from two tenants each send requests at a fixed rate for a fixed time (kept under the
per-user rate limit of 20/s, so the numbers are service time, not throttling). Run from the
repository root with the compose stack up:

    uv run --project services/api python tests/load/api_latency.py [seconds] [rate_per_user]

Writes tests/load/results/api-latency-<timestamp>.txt.
"""
import asyncio
import datetime
import statistics
import sys
import time
from collections import defaultdict

import httpx

API = "http://127.0.0.1:8000"
TOKEN_URL = "http://127.0.0.1:8080/realms/fleetpulse/protocol/openid-connect/token"
USERS = [("acme.manager", "acme-demo-2026"), ("acme.viewer", "acme-view-2026"), ("zenith.manager", "zenith-demo-2026")]
ENDPOINTS = [
    "/v1/vehicles?limit=50",
    "/v1/risk?limit=50",
    "/v1/alerts?limit=50",
    "/v1/fleet/summary",
    "/v1/maintenance/overview",
    "/v1/vehicles/{vid}",
]


def pct(values: list[float], p: float) -> float:
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


async def user_loop(client: httpx.AsyncClient, token: str, seconds: float, rate: float,
                    lat: dict[str, list[float]], codes: dict[int, int]) -> None:
    headers = {"Authorization": f"Bearer {token}"}
    first = (await client.get("/v1/vehicles?limit=1", headers=headers)).json()["items"][0]["id"]
    end, i = time.perf_counter() + seconds, 0
    while time.perf_counter() < end:
        tick = time.perf_counter()
        path = ENDPOINTS[i % len(ENDPOINTS)]
        t0 = time.perf_counter()
        resp = await client.get(path.format(vid=first), headers=headers)
        lat[path].append((time.perf_counter() - t0) * 1000)
        codes[resp.status_code] += 1
        i += 1
        await asyncio.sleep(max(0.0, 1 / rate - (time.perf_counter() - tick)))


async def main(seconds: float, rate: float) -> None:
    lat: dict[str, list[float]] = defaultdict(list)
    codes: dict[int, int] = defaultdict(int)
    async with httpx.AsyncClient(base_url=API, timeout=30) as client:
        tokens = []
        for user, password in USERS:
            r = httpx.post(TOKEN_URL, data={"grant_type": "password", "client_id": "fleetpulse-cli",
                                            "username": user, "password": password}, timeout=30)
            r.raise_for_status()
            tokens.append(r.json()["access_token"])
        started = time.perf_counter()
        await asyncio.gather(*(user_loop(client, t, seconds, rate, lat, codes) for t in tokens))
        elapsed = time.perf_counter() - started

    every = [v for vs in lat.values() for v in vs]
    lines = [
        f"API latency, {datetime.datetime.now(datetime.timezone.utc):%Y-%m-%d %H:%M} UTC, local Docker stack "
        f"(simulator streaming 100K vehicles)",
        f"{len(USERS)} users x {rate}/s for {seconds:.0f}s: {len(every)} requests, "
        f"{len(every) / elapsed:.1f} req/s, status codes {dict(codes)}",
        "",
        f"{'endpoint':32} {'n':>5} {'p50 ms':>8} {'p95 ms':>8} {'p99 ms':>8} {'max ms':>8}",
    ]
    for path, vs in list(lat.items()) + [("ALL", every)]:
        lines.append(f"{path:32} {len(vs):5} {statistics.median(vs):8.1f} {pct(vs, 95):8.1f} "
                     f"{pct(vs, 99):8.1f} {max(vs):8.1f}")
    report = "\n".join(lines)
    print(report)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    with open(f"tests/load/results/api-latency-{stamp}.txt", "w", encoding="utf8") as f:
        f.write(report + "\n")


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 120, float(sys.argv[2]) if len(sys.argv) > 2 else 10))
