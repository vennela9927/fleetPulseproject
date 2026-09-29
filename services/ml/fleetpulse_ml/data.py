"""Loads the inputs: per-vehicle daily rollups (ClickHouse), vehicle facts and breakdown
labels (Postgres). The ML job is a platform service: it reads across tenants with the
pipeline's credentials, and writes scores back per tenant."""

import os
from dataclasses import dataclass

import clickhouse_connect
import pandas as pd
import psycopg

# Fault codes the rollup exposes as per-day presence flags. Includes benign background codes
# (P0420, P0171, U0100) so "any fault code" is not mistaken for a failure signal.
DTC_CODES = ["P0128", "P0217", "P0301", "P0300", "P0562", "P0AFA", "P0A80", "P0AA6",
             "P0741", "P0700", "C0035", "C0265", "U0100", "P0420", "P0171"]


@dataclass(frozen=True)
class Config:
    clickhouse_host: str = os.environ.get("CLICKHOUSE_HOST", "127.0.0.1")
    clickhouse_port: int = int(os.environ.get("CLICKHOUSE_PORT", "8123"))
    clickhouse_user: str = os.environ.get("CLICKHOUSE_USER", "fleet")
    clickhouse_password: str = os.environ.get("CLICKHOUSE_PASSWORD", "clickhouse_dev")
    postgres_dsn: str = os.environ.get(
        "POSTGRES_DSN", "postgresql://fleet_service:fleet_service_dev@127.0.0.1:5432/fleet")
    model_dir: str = os.environ.get("MODEL_DIR", "models")


def daily(cfg: Config) -> pd.DataFrame:
    """One row per vehicle-day of operation, merged from the vehicle_daily rollup.

    Read one day at a time: merging every vehicle-day's fault-code arrays in a single query
    needs more memory than the ClickHouse container has."""
    client = clickhouse_connect.get_client(host=cfg.clickhouse_host, port=cfg.clickhouse_port,
                                           username=cfg.clickhouse_user, password=cfg.clickhouse_password)
    flags = ",\n".join(f"has(codes, '{c}') AS dtc_{c}" for c in DTC_CODES)
    # The flag columns come from the DTC_CODES constant above, never from input.
    sql = f"""
        SELECT vin, day, samples, km, coolant_avg, coolant_max, batt_v_min, batt_v_avg, soh_min, rpm_avg,
               dtc_events, harsh_events, idle_samples, {flags}
        FROM (
            SELECT vin, day,
                   countMerge(samples) AS samples,
                   maxMerge(km_end) - minMerge(km_start) AS km,
                   avgMerge(coolant_avg) AS coolant_avg,
                   maxMerge(coolant_max) AS coolant_max,
                   minMerge(batt_v_min) AS batt_v_min,
                   avgMerge(batt_v_avg) AS batt_v_avg,
                   minMerge(soh_min) AS soh_min,
                   avgMerge(rpm_avg) AS rpm_avg,
                   sumMerge(dtc_events) AS dtc_events,
                   countIfMerge(harsh_events) AS harsh_events,
                   countIfMerge(idle_samples) AS idle_samples,
                   groupUniqArrayArrayMerge(dtc_distinct) AS codes
            FROM fleet.vehicle_daily
            WHERE day = {{day:Date}}
            GROUP BY vin, day)"""  # noqa: S608
    days = [r[0] for r in client.query("SELECT DISTINCT day FROM fleet.vehicle_daily ORDER BY day").result_rows]
    df = pd.concat([client.query_df(sql, parameters={"day": d}) for d in days], ignore_index=True)
    df = df.sort_values(["vin", "day"], ignore_index=True)
    df["vin"] = df["vin"].astype(str).str.strip()
    df["day"] = pd.to_datetime(df["day"])
    return df


def vehicles(cfg: Config) -> pd.DataFrame:
    with psycopg.connect(cfg.postgres_dsn) as conn:
        rows = conn.execute("""
            SELECT v.id AS vehicle_id, trim(v.vin) AS vin, v.tenant_id::text AS tenant_id, v.model_year,
                   m.powertrain, o.code AS oem
            FROM vehicle v JOIN vehicle_model m ON m.id = v.model_id JOIN oem o ON o.id = m.oem_id""").fetchall()
    return pd.DataFrame(rows, columns=["vehicle_id", "vin", "tenant_id", "model_year", "powertrain", "oem"])


def breakdowns(cfg: Config) -> pd.DataFrame:
    """Observed breakdowns and what the breakdown and the planned repair cost."""
    with psycopg.connect(cfg.postgres_dsn) as conn:
        rows = conn.execute("""
            SELECT b.vehicle_id, b.occurred_at, b.component, b.cost_usd AS breakdown_cost_usd,
                   r.cost_usd AS repair_cost_usd
            FROM maintenance_event b
            LEFT JOIN maintenance_event r ON r.vehicle_id = b.vehicle_id AND r.kind = 'REPAIR'
                 AND r.component = b.component AND r.occurred_at > b.occurred_at
            WHERE b.kind = 'BREAKDOWN'""").fetchall()
    df = pd.DataFrame(rows, columns=["vehicle_id", "occurred_at", "component", "breakdown_cost_usd",
                                     "repair_cost_usd"])
    df["occurred_at"] = pd.to_datetime(df["occurred_at"], utc=True).dt.tz_convert(None)
    for c in ("breakdown_cost_usd", "repair_cost_usd"):
        df[c] = df[c].astype(float)
    return df
