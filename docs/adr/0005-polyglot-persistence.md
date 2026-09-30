# ADR 0005: Four stores, each for one access pattern

**Status:** accepted, 2026-09-28 (recorded 2026-09-30)

## Context

100K vehicles report every 10 seconds: 10K events a second, 860M a day. The same platform also
holds a small amount of data that must never be wrong (who owns which vehicle, bookings,
approvals, the audit trail), serves a live map that changes every second, and answers "which past
breakdowns looked like this one". No single store is good at all four.

## Decision

| Data | Store | Why this store | Why not the others |
|---|---|---|---|
| Tenants, vehicles, alerts, bookings, copilot proposals, audit log, risk scores | **Postgres 16** (relational, 3NF) | Transactions and constraints: a booking and its audit row commit together; foreign keys and CHECKs make bad states impossible; row-level security enforces tenant isolation inside the database. | ClickHouse has no transactions or row-level updates; Redis has no constraints or durability guarantees. |
| Raw telemetry and its rollups (per minute, per day) | **ClickHouse** (columnar) | Scans billions of rows by vehicle and time in seconds; ~10x compression; materialised views keep rollups up to date on insert; cold partitions tier to S3-compatible storage. | Postgres would need heavy partitioning and still scan row by row; a 21-day backfill is 49M rows for the demo fleet alone. |
| Live state per vehicle, the live map, alert fan-out | **Redis** (key-value, NoSQL) | Sub-millisecond reads and writes; one hash per vehicle updated in place; a per-tenant map read model; pub/sub for the live alert feed. Everything in it can be rebuilt from Kafka, so it needs no persistence. | Postgres at 10K updates/s would churn MVCC and vacuum for data that is overwritten seconds later. |
| Fault signatures for "similar past failures" | **pgvector** (inside Postgres) | Thousands of 16-number fingerprints, joined to vehicles and repairs, under the same transactions and backups. A nearest-neighbour search is one `ORDER BY embedding <=> ...`. | A separate vector database adds a system to run for a dataset that grows with breakdowns, not telemetry. It becomes worthwhile past millions of vectors. |

Kafka sits in front of all of them as the durable log: every store downstream of it can be rebuilt
by replaying it, which is what makes the exactly-once sink (ADR 0001) and a rebuildable Redis safe.

## Deliberate denormalisations

Marked `DENORM` in `infra/postgres/init/01_schema.sql`: `tenant_id` is copied onto `vehicle`,
`alert`, `risk_score` and `maintenance_event` so row-level security needs no join. A composite
foreign key (`vehicle(fleet_id, tenant_id)` references `fleet(id, tenant_id)`) makes an
inconsistent copy impossible, so the copy cannot drift.

## Consequences

- Four stores to operate. Each is available as a managed service on every major cloud (RDS or Cloud
  SQL, ClickHouse Cloud, ElastiCache or Memorystore), so production runs none of them by hand.
- Tenant isolation has to be enforced in each store separately (ADR 0002).
- Reads that need both worlds (a vehicle page with its history) are two queries from the API, not a
  join; the API does this in parallel.
