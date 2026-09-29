#!/bin/bash
# Read-only ClickHouse user for the API, with tenant isolation enforced by row policies:
# the counterpart of Postgres row-level security. The API sets SQL_tenant_id on every query
# from the verified token; a query without it fails (getSetting throws) instead of
# returning every tenant's rows. The pipeline and the ML job use the 'fleet' user, which no
# policy restricts.
set -euo pipefail

clickhouse client --user "${CLICKHOUSE_USER}" --password "${CLICKHOUSE_PASSWORD}" --multiquery <<SQL
CREATE SETTINGS PROFILE IF NOT EXISTS api_profile SETTINGS
    readonly = 2,                       -- may set per-query settings, may not write
    max_execution_time = 15,
    max_memory_usage = 300000000,
    max_result_rows = 200000;
CREATE USER IF NOT EXISTS fleet_api IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_API_PASSWORD:-clickhouse_api_dev}'
    SETTINGS PROFILE 'api_profile';
GRANT SELECT ON fleet.telemetry TO fleet_api;
GRANT SELECT ON fleet.telemetry_1m TO fleet_api;
GRANT SELECT ON fleet.vehicle_daily TO fleet_api;
CREATE ROW POLICY IF NOT EXISTS tenant_isolation ON fleet.telemetry, fleet.telemetry_1m, fleet.vehicle_daily
    FOR SELECT USING tenant_id = toUUID(getSetting('SQL_tenant_id')) TO fleet_api;
SQL
