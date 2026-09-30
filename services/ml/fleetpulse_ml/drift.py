"""Feed drift: is each maker's data still distributed like its own recent past?

A firmware update can change a feed without breaking it: speed in mph still labelled km/h passes
every validation rule and raises no alert, but it quietly skews trip distances, harsh-driving
rates and the failure model's inputs. This job compares each maker's last few minutes with its
previous 40 minutes (when that history is continuous), field by field, using the population stability index (PSI), and records the
result for the Vehicle makers page. Conventional reading: under 0.1 stable, 0.1-0.25 watch,
above 0.25 drifted.

    uv run python -m fleetpulse_ml.drift            # one check
    uv run python -m fleetpulse_ml.drift --watch 60 # every minute
"""

import argparse
import time
from datetime import UTC, datetime

import clickhouse_connect
import numpy as np
import pandas as pd
import psycopg

from . import data

FIELDS = ["speed_kmh", "rpm", "coolant_c", "batt_v", "fuel_pct", "soc_pct"]
WATCH, DRIFT = 0.1, 0.25
MIN_ROWS = 300


def psi(baseline: np.ndarray, current: np.ndarray, bins: int = 10) -> float:
    """Population stability index over the baseline's deciles (ties merged)."""
    edges = np.unique(np.quantile(baseline, np.linspace(0, 1, bins + 1)[1:-1]))
    edges = np.concatenate([[-np.inf], edges, [np.inf]])
    b = np.histogram(baseline, edges)[0] / len(baseline)
    c = np.histogram(current, edges)[0] / len(current)
    b, c = np.clip(b, 1e-4, None), np.clip(c, 1e-4, None)
    return float(np.sum((c - b) * np.log(c / b)))


def hint(field: str, before: float, after: float, fleet_ratio: float = 1.0) -> str | None:
    """A likely cause for a shifted mean, when it matches a common unit mistake. ``fleet_ratio`` is
    how much the other makers' mean moved over the same window, so a fleet-wide change (everyone
    parked at night) is divided out before the ratio is read."""
    if not before or abs(before) < 1e-6 or fleet_ratio <= 0:
        return None
    ratio = after / before / fleet_ratio
    if field == "speed_kmh" and 0.58 < ratio < 0.66:
        return "about 0.62x its usual level: looks like mph sent as km/h"
    if field == "speed_kmh" and 1.52 < ratio < 1.70:
        return "about 1.6x its usual level: looks like km/h converted twice"
    if field == "coolant_c" and abs(after - (before * 9 / 5 + 32)) < 0.1 * after:
        return "matches the old values converted to Fahrenheit"
    if 900 < ratio < 1100:
        return "about 1000x its usual level: looks like milli-units"
    return None


def check(cfg: data.Config, baseline_minutes: int = 60, current_minutes: int = 10) -> pd.DataFrame:
    """Each maker's last ``current_minutes`` against its own recent past: from ``baseline_minutes``
    ago up to twice the current window ago. A maker is judged only when its baseline is continuous
    (data in at least 90% of its minutes): after a restart or an outage the check says nothing rather
    than comparing against a gap. A long baseline spanning restarts raised false alarms."""
    client = clickhouse_connect.get_client(host=cfg.clickhouse_host, port=cfg.clickhouse_port,
                                           username=cfg.clickhouse_user, password=cfg.clickhouse_password)
    cols = ", ".join(FIELDS)
    # Sampled by event, so a check reads a few hundred thousand rows whatever the fleet size.
    sql = f"""
        SELECT oem, {cols} FROM fleet.telemetry
        WHERE ts >= now() - INTERVAL {{start:UInt32}} SECOND AND ts < now() - INTERVAL {{end:UInt32}} SECOND
          AND cityHash64(vin, seq) % {{mod:UInt32}} = 0"""  # noqa: S608  column list is the FIELDS constant
    settings = {"max_threads": 2}
    window = {"start": baseline_minutes * 60, "end": 2 * current_minutes * 60}
    base = client.query_df(sql, parameters={**window, "mod": 20}, settings=settings)
    cur = client.query_df(sql, parameters={"start": current_minutes * 60, "end": 0, "mod": 5}, settings=settings)
    covered = dict(client.query("""
        SELECT oem, uniqExact(toStartOfMinute(ts)) FROM fleet.telemetry
        WHERE ts >= now() - INTERVAL {start:UInt32} SECOND AND ts < now() - INTERVAL {end:UInt32} SECOND
        GROUP BY oem""", parameters=window, settings=settings).result_rows)
    need = 0.9 * (window["start"] - window["end"]) / 60
    rows = []
    if cur.empty or base.empty:  # traffic paused or just started: nothing to compare yet
        return classify(pd.DataFrame(rows))
    for oem in sorted(o for o in set(cur["oem"]) if covered.get(o, 0) >= need):
        b_oem, c_oem = base[base["oem"] == oem], cur[cur["oem"] == oem]
        for f in FIELDS:
            b, c = b_oem[f].dropna().to_numpy(float), c_oem[f].dropna().to_numpy(float)
            if len(b) < MIN_ROWS or len(c) < MIN_ROWS:
                continue
            rows.append({"oem_code": oem, "field": f, "psi": round(psi(b, c), 3),
                         "baseline_mean": round(float(b.mean()), 2), "current_mean": round(float(c.mean()), 2),
                         "current_n": len(c)})
    return classify(pd.DataFrame(rows))


def classify(result: pd.DataFrame) -> pd.DataFrame:
    """Drift is a change in one maker's feed, not in the fleet. Time of day, weather or a simulator
    restart move every maker together, so each maker's PSI is judged against the median of the other
    makers for the same field: only the excess over the fleet's own shift counts."""
    if result.empty:
        return result.assign(status=[], hint=[])
    statuses, hints = [], []
    for r in result.itertuples(index=False):
        peers = result[(result["field"] == r.field) & (result["oem_code"] != r.oem_code)]
        fleet = float(peers["psi"].median()) if len(peers) else 0.0
        moved = (peers["current_mean"] / peers["baseline_mean"]).replace([np.inf, -np.inf], np.nan).dropna()
        fleet_ratio = float(moved.median()) if len(moved) else 1.0
        excess = r.psi - fleet
        status = "DRIFT" if r.psi > DRIFT and excess > DRIFT else "WATCH" if r.psi > WATCH and excess > WATCH else "OK"
        statuses.append(status)
        if status != "OK":
            hints.append(hint(r.field, r.baseline_mean, r.current_mean, fleet_ratio)
                         or f"other makers' PSI for this field: {fleet:.2f}")
        elif r.psi > DRIFT:
            hints.append("every maker shifted together: an operational change, not this feed")
        else:
            hints.append(None)
    return result.assign(status=statuses, hint=hints)


def save(cfg: data.Config, result: pd.DataFrame) -> None:
    checked_at = datetime.now(UTC)
    with psycopg.connect(cfg.postgres_dsn) as conn, conn.transaction():
        conn.cursor().executemany("""
            INSERT INTO feed_drift (checked_at, oem_code, field, psi, baseline_mean, current_mean, current_n,
                                    status, hint)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            [(checked_at, r.oem_code, r.field, r.psi, r.baseline_mean, r.current_mean, r.current_n, r.status, r.hint)
             for r in result.itertuples(index=False)])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--watch", type=int, metavar="SECONDS", help="repeat every SECONDS")
    args = parser.parse_args()
    cfg = data.Config()
    while True:
        try:
            result = check(cfg)
            save(cfg, result)
        except Exception as e:  # a store restarting must not end the watch; the next check retries
            if not args.watch:
                raise
            print(f"{datetime.now(UTC):%H:%M:%S} check failed, retrying in {args.watch}s: {e!r}", flush=True)
            time.sleep(args.watch)
            continue
        flagged = result[result["status"] != "OK"] if len(result) else result
        print(f"{datetime.now(UTC):%H:%M:%S} checked {len(result)} maker fields; "
              + ("; ".join(f"{r.oem_code} {r.field} PSI {r.psi} ({r.status})" for r in flagged.itertuples())
                 or "all stable"), flush=True)
        if not args.watch:
            return
        time.sleep(args.watch)


if __name__ == "__main__":
    main()
