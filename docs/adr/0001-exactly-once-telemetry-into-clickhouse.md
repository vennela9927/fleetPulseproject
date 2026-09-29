# ADR 0001: Exactly-once telemetry into ClickHouse

**Status:** accepted, 2026-09-29

## Context

Telemetry reaches ClickHouse through `fleet.telemetry` and two rollups fed by materialized
views on insert (`telemetry_1m`, `vehicle_daily`). The failure-prediction model and the
dashboards read the rollups.

The raw table is a `ReplacingMergeTree` on `(vin, ts, seq)`, so a row delivered twice is
merged away. The rollups have no such key: a batch inserted twice is counted twice. That is
exactly what happens with a plain at-least-once consumer when it crashes after the insert but
before committing its Kafka offset. We saw the same failure in the historical backfill, where
a retried insert double-counted 400,008 rows.

ClickHouse can drop a repeated insert when it carries the same `insert_deduplication_token`
(with `non_replicated_deduplication_window` set on each table and
`deduplicate_blocks_in_dependent_materialized_views=1`). The catch is that the retry must be
the same batch. A consumer restarting after a crash normally forms different batches, because
more records are available and its flush timer starts over.

## Decision

The ClickHouse sink batches **per Kafka partition** and records each batch's boundary in Kafka
before inserting it:

1. Commit `offset = first` with metadata `intent:first-last`.
2. Insert with token `topicId:partition:first-last`.
3. Commit `offset = last + 1`.

Whoever owns the partition next reads the committed metadata. If it holds an intent, the new
owner closes its first batch at exactly `last`, which reproduces the token, and ClickHouse keeps
one copy. The topic id is part of the token, so a recreated topic (with offsets back at 0) cannot
collide with tokens still inside the deduplication window.

The window is 10,000 blocks per table. At the sink's ~5 inserts/s (24 partitions, 5 s flush),
that covers about 30 minutes of downtime before a replayed batch could fall out of it.

## Alternatives considered

- **Query the rollups with FINAL or re-aggregate from raw.** This moves the cost to every read,
  and the rollups exist to make reads cheap.
- **ClickHouse Kafka engine / Kafka Connect sink.** Both are at-least-once into materialized
  views unless run with the connector's exactly-once state store (KeeperMap), which needs
  ClickHouse Keeper: another stateful component for a single-node deployment.
- **Transactions in ClickHouse.** These are experimental in 24.8 and do not span materialized views.
- **One insert across all partitions.** Fewer, larger parts, but after a rebalance no single
  consumer owns every partition of a recorded batch, so the batch cannot be reproduced.

## Consequences

- Insert rate is bounded by partitions and flush interval (≈5/s), independent of event rate;
  parts stay large enough for the merge pool at 100K events/s.
- Two offset commits per batch instead of one: negligible at ≈5 batches/s.
- ClickHouse lags Kafka by up to the flush interval (5 s). Live views read Redis, not ClickHouse.
- If ClickHouse is down, the sink pauses its partitions and retries with backoff while staying
  in the consumer group. Data waits in Kafka (3-day retention) and alerts are unaffected,
  because they use a separate consumer group.
- Covered by `TelemetryBatcherTest`, which places crashes between each step.
