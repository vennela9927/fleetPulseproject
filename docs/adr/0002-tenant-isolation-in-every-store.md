# ADR 0002: Tenant isolation enforced by every data store, not by API code

**Status:** accepted, 2026-09-29

## Context

FleetPulse serves several fleet operators (tenants) from shared databases. A single missing
`WHERE tenant_id = ...` in one API query would leak another tenant's vehicles, positions or
alerts. The API reads three stores: Postgres (business data), ClickHouse (telemetry history)
and Redis (live state).

## Decision

Identity comes from Keycloak (OIDC). Each tenant is a Keycloak group whose `tenant_id`
attribute is mapped into the access token. The API accepts only RS256 tokens signed by a key
from the realm's JWKS, and checks issuer, audience (`fleetpulse-api`) and expiry. From then
on, **the tenant comes only from the verified token**, and each store enforces it on its own:

| Store | Mechanism | If the API forgets the filter |
|---|---|---|
| Postgres | The API connects as `fleet_app`, subject to forced row-level security. Every request runs in a transaction that sets `app.tenant_id` with `set_config(..., true)` (transaction-local, so pooled connections cannot carry it over). | RLS returns only the caller's rows. |
| ClickHouse | The API connects as `fleet_api` (read-only profile) with row policies `tenant_id = toUUID(getSetting('SQL_tenant_id'))`; the API sends the setting on every query. | The policy still filters. If the setting is missing, the query **fails** instead of returning everything. |
| Redis | Tenant-scoped keys (`geo:{tenant}`, `map:{tenant}`, `alerts:{tenant}` channel), named from the token. Per-VIN hashes carry their owner, which is checked before use. | No key names another tenant. |

Another tenant's resource returns the same 404 as a missing one, so ids cannot be probed.

## Alternatives considered

- **Filter in application code only.** This is simplest, but a single bug is a breach. The
  database policies turn it into a missing-data bug instead.
- **A database (or schema) per tenant.** Stronger isolation, but 3 stores × N tenants to
  migrate and operate, and cross-tenant analytics (the ML model trains on all fleets) becomes
  a federation problem.
- **API-issued tokens instead of Keycloak.** Less infrastructure, but then password storage,
  MFA, brute-force protection and SSO are ours to build. Keycloak's realm export is
  version-controlled (`infra/keycloak/fleetpulse-realm.json`).

## Consequences

- The pipeline (`fleet_service`, ClickHouse `fleet`) writes across tenants and bypasses these
  policies by design. Its credentials must never reach the API.
- Every API query pays for one `set_config` round trip (~0.1 ms) and RLS predicates. Indexes
  lead with `tenant_id` where it matters.
- Tested in `services/api/tests/test_api_integration.py` with real tokens for two tenants.
  The ClickHouse policy was checked directly: no setting → error 115; Acme sees only Acme
  rows, even when it asks for Zenith's explicitly; writes are denied.
