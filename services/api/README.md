# FleetPulse API

FastAPI service over Postgres (business data), ClickHouse (telemetry history) and Redis (live
state). Tenant isolation is enforced by each store, not only by this code: see
[ADR 0002](../../docs/adr/0002-tenant-isolation-in-every-store.md).

## Run

```bash
docker compose up -d            # from the repo root: Postgres, Redis, ClickHouse, Keycloak, ...
cd services/api
uv sync
uv run python -m fleetpulse_api # http://127.0.0.1:8000, OpenAPI docs at /docs
```

Use `127.0.0.1` rather than `localhost` for command-line clients on Windows. `localhost` tries
IPv6 first and costs ~200 ms per connection.

## Get a token (development)

```bash
curl -s -X POST http://localhost:8080/realms/fleetpulse/protocol/openid-connect/token \
  -d grant_type=password -d client_id=fleetpulse-cli \
  -d username=acme.manager -d password=acme-demo-2026 | jq -r .access_token
```

| User | Password | Tenant | Role |
|---|---|---|---|
| acme.manager | acme-demo-2026 | Acme Logistics | fleet_manager |
| acme.viewer | acme-view-2026 | Acme Logistics | fleet_viewer |
| zenith.manager | zenith-demo-2026 | Zenith Rentals | fleet_manager |
| ops.admin | ops-demo-2026 | FleetPulse Operations (no vehicles) | platform_admin |

The dashboard uses the `fleetpulse-web` client (authorization code + PKCE). The password
grant on `fleetpulse-cli` exists for scripts and tests only.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | /v1/me | caller, tenant, roles |
| GET | /v1/fleets | fleets with vehicle counts |
| GET | /v1/vehicles | filters: fleet_id, powertrain, status, vin_prefix; keyset `cursor` |
| GET | /v1/vehicles/{id} | master data, live state, open alerts, latest risk score |
| GET | /v1/vehicles/{id}/telemetry | `resolution` raw (≤24 h) / 1m (≤7 d) / 1d (≤90 d); costs 5 tokens |
| GET | /v1/live/vehicles | map viewport `bbox=minLon,minLat,maxLon,maxLat`, column-oriented rows; costs 5 |
| GET | /v1/fleet/summary | live status counts, open alerts by severity |
| GET | /v1/alerts | filters: status, severity, rule, vehicle_id; newest first, keyset `cursor` |
| POST | /v1/alerts/{id}/acknowledge, /resolve | fleet_manager only; audited |
| GET | /v1/alerts/stream | Server-Sent Events of new alerts for the caller's tenant |
| GET | /v1/audit/verify | recomputes the audit log hash chain (fleet_manager) |
| GET | /v1/ops/pipeline | events sent by the simulator vs. stored, for the caller's tenant |
| POST | /v1/ops/inject, /burst, /pause | demo controls (fleet_manager, audited; off when `API_DEMO_CONTROLS=false`) |
| GET | /v1/oem-mappings, /v1/oem-mappings/{id}, /v1/oem-mappings/status | maker mappings, what the pipeline runs, replay progress |
| POST | /v1/oem-mappings | propose a maker's mapping as a DRAFT (platform_admin) |
| POST | /v1/oem-mappings/{id}/preview | compile it and map the maker's parked events (platform_admin) |
| POST | /v1/oem-mappings/{id}/approve | activate: DB change and Kafka publish succeed or fail together (platform_admin) |

Errors are `application/problem+json` and carry the request id (`X-Request-ID`).
Rate limit: a per-user token bucket (default 20/s, burst 60) shared across replicas through
Redis, answering 429 with `Retry-After`.

## Test

```bash
uv run pytest        # unit tests always; integration tests when the compose stack is up
uv run ruff check .
```
