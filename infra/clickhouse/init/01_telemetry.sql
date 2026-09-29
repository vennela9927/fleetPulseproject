-- Telemetry store (AP data: high write rate, eventual consistency is acceptable).
--
-- Partitioning : one partition per day, so retention and tiering drop whole parts.
-- Sort key     : (vin, ts, seq) keeps a vehicle's history physically contiguous,
--                so "vehicle X over the last 7 days" reads a few granules.
-- Idempotency  : ReplacingMergeTree collapses rows with the same sort key. The sink is
--                at-least-once; a redelivered event has identical (vin, ts, seq) and is
--                merged away. Queries that must be exact use FINAL.
--                Bulk loads also send an insert_deduplication_token per batch. The rollup
--                tables below have no such merge key, so without insert deduplication a
--                retried batch would be counted twice in them. The window (10000 blocks)
--                covers ~30 minutes of the live sink's ~5 inserts/s, so a sink that
--                restarts within that time and replays its last batch is still deduplicated.
-- Tiering      : hot on local SSD for 3 days, then moved to S3 (SeaweedFS locally), deleted at 90.

CREATE DATABASE IF NOT EXISTS fleet;

CREATE TABLE IF NOT EXISTS fleet.telemetry
(
    vin         FixedString(17),
    tenant_id   UUID,
    fleet_id    UInt32,
    oem         LowCardinality(String),
    powertrain  LowCardinality(String),
    seq         UInt64,
    ts          DateTime64(3, 'UTC'),
    ingest_ts   DateTime64(3, 'UTC'),
    lat         Float64,
    lon         Float64,
    geohash6    String MATERIALIZED geohashEncode(lon, lat, 6),
    speed_kmh   Float32,
    odo_km      Float64,
    engine_on   UInt8,
    rpm         UInt16,
    fuel_pct    Nullable(Float32),
    soc_pct     Nullable(Float32),
    soh_pct     Nullable(Float32),
    coolant_c   Nullable(Float32),
    batt_v      Float32,
    dtc         Array(LowCardinality(String)),
    evt         LowCardinality(String),

    INDEX idx_dtc dtc TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_evt evt TYPE set(32) GRANULARITY 4
)
ENGINE = ReplacingMergeTree(ingest_ts)
PARTITION BY toYYYYMMDD(ts)
ORDER BY (vin, ts, seq)
TTL toDateTime(ts) + INTERVAL 3 DAY TO VOLUME 'cold',
    toDateTime(ts) + INTERVAL 90 DAY DELETE
SETTINGS storage_policy = 'tiered', index_granularity = 8192, non_replicated_deduplication_window = 10000;

-- Per-vehicle, per-minute rollup for dashboards (reads ~60x fewer rows than raw).
CREATE TABLE IF NOT EXISTS fleet.telemetry_1m
(
    vin           FixedString(17),
    tenant_id     UUID,
    minute        DateTime('UTC'),
    samples       AggregateFunction(count),
    avg_speed     AggregateFunction(avg, Float32),
    max_coolant   AggregateFunction(max, Nullable(Float32)),
    min_batt_v    AggregateFunction(min, Float32),
    last_lat      AggregateFunction(argMax, Float64, DateTime64(3, 'UTC')),
    last_lon      AggregateFunction(argMax, Float64, DateTime64(3, 'UTC'))
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMMDD(minute)
ORDER BY (tenant_id, vin, minute)
TTL minute + INTERVAL 30 DAY DELETE
SETTINGS non_replicated_deduplication_window = 10000;

CREATE MATERIALIZED VIEW IF NOT EXISTS fleet.telemetry_1m_mv TO fleet.telemetry_1m AS
SELECT vin, tenant_id, toStartOfMinute(ts) AS minute,
       countState()              AS samples,
       avgState(speed_kmh)       AS avg_speed,
       maxState(coolant_c)       AS max_coolant,
       minState(batt_v)          AS min_batt_v,
       argMaxState(lat, ts)      AS last_lat,
       argMaxState(lon, ts)      AS last_lon
FROM fleet.telemetry
GROUP BY vin, tenant_id, minute;

-- Per-vehicle daily features: the input to the failure-prediction model.
CREATE TABLE IF NOT EXISTS fleet.vehicle_daily
(
    vin            FixedString(17),
    tenant_id      UUID,
    day            Date,
    powertrain     LowCardinality(String),
    samples        AggregateFunction(count),
    km_start       AggregateFunction(min, Float64),
    km_end         AggregateFunction(max, Float64),
    coolant_avg    AggregateFunction(avg, Nullable(Float32)),
    coolant_max    AggregateFunction(max, Nullable(Float32)),
    batt_v_min     AggregateFunction(min, Float32),
    batt_v_avg     AggregateFunction(avg, Float32),
    soh_min        AggregateFunction(min, Nullable(Float32)),
    rpm_avg        AggregateFunction(avg, UInt16),
    dtc_events     AggregateFunction(sum, UInt64),
    dtc_distinct   AggregateFunction(groupUniqArrayArray, Array(LowCardinality(String))),
    harsh_events   AggregateFunction(countIf, UInt8),
    idle_samples   AggregateFunction(countIf, UInt8)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (vin, day)
SETTINGS non_replicated_deduplication_window = 10000;

CREATE MATERIALIZED VIEW IF NOT EXISTS fleet.vehicle_daily_mv TO fleet.vehicle_daily AS
SELECT vin, tenant_id, toDate(ts) AS day, any(powertrain) AS powertrain,
       countState()                                       AS samples,
       minState(odo_km)                                   AS km_start,
       maxState(odo_km)                                   AS km_end,
       avgState(coolant_c)                                AS coolant_avg,
       maxState(coolant_c)                                AS coolant_max,
       minState(batt_v)                                   AS batt_v_min,
       avgState(batt_v)                                   AS batt_v_avg,
       minState(soh_pct)                                  AS soh_min,
       avgState(rpm)                                      AS rpm_avg,
       sumState(toUInt64(length(dtc)))                    AS dtc_events,
       groupUniqArrayArrayState(dtc)                      AS dtc_distinct,
       countIfState(evt IN ('HARSH_BRAKE', 'HARSH_ACCEL')) AS harsh_events,
       countIfState(engine_on = 1 AND speed_kmh < 1)      AS idle_samples
FROM fleet.telemetry
GROUP BY vin, tenant_id, day;
