"""Scores the fleet with the active model and writes the results where the product reads them:
risk_score (every vehicle), HIGH_FAILURE_RISK alerts (above the validated threshold), and
fault_signature embeddings that let the copilot find past breakdowns resembling a vehicle now.
"""

import json
import os
from datetime import UTC, datetime

import lightgbm as lgb
import numpy as np
import pandas as pd
import psycopg

from . import data
from .features import CATEGORICAL, features_asof, label

FACTOR_LABELS = {
    "coolant_trend": "Coolant temperature rising", "coolant_max_7d": "High peak coolant temperature",
    "coolant_max_3d": "High coolant temperature (3 days)", "coolant_avg_3d": "Engine running hot",
    "batt_min_7d": "Low 12 V battery voltage", "batt_min_3d": "Low 12 V battery voltage (3 days)",
    "batt_trend": "12 V battery voltage falling", "soh_drop_7d": "Battery health dropping",
    "soh_min_7d": "Low battery health", "rpm_trend": "Engine RPM drifting", "rpm_avg_3d": "Unusual engine RPM",
    "harsh_trend": "More harsh braking than usual", "harsh_per_100km_3d": "Frequent harsh braking",
    "dtc_events_7d": "Many fault codes this week", "dtc_events_3d": "Many fault codes (3 days)",
    "serious_dtc_days_3d": "Serious fault codes reported",
    "dtc_events_1d": "Fault codes reported yesterday", "batt_v_min_1d": "Low 12 V battery voltage yesterday",
    "coolant_max_1d": "High coolant temperature yesterday", "km_7d": "Distance driven this week",
    "idle_ratio_7d": "Much idling", "age_years": "Vehicle age", "days_active_7d": "Days in use this week",
}

# The pre-failure pattern, as a 16-dimensional signature for similarity search (pgvector).
SIGNATURE = ["coolant_max_7d", "coolant_trend", "batt_min_7d", "batt_trend", "soh_drop_7d", "rpm_trend",
             "harsh_trend", "dtc_events_7d", "dtc_P0128_days_7d", "dtc_P0217_days_7d", "dtc_P0301_days_7d",
             "dtc_P0562_days_7d", "dtc_P0AFA_days_7d", "dtc_P0741_days_7d", "dtc_C0035_days_7d", "idle_ratio_7d"]


def factor_label(feature: str) -> str:
    if feature in FACTOR_LABELS:
        return FACTOR_LABELS[feature]
    if feature in ("oem", "powertrain"):
        return "Maker or powertrain with a higher failure rate"
    if feature.startswith("dtc_") and feature.endswith("_days_7d"):
        return f"Fault code {feature[4:-8]} reported"
    return feature.replace("_", " ")


def load_active(cfg: data.Config) -> tuple[dict, lgb.Booster, lgb.Booster]:
    with psycopg.connect(cfg.postgres_dsn) as conn:
        row = conn.execute("SELECT version FROM risk_model WHERE is_active").fetchone()
    if row is None:
        raise SystemExit("no active model: run fleetpulse-train first")
    path = os.path.join(cfg.model_dir, row[0])
    with open(os.path.join(path, "meta.json")) as fh:
        meta = json.load(fh)
    return meta, lgb.Booster(model_file=os.path.join(path, "risk.txt")), lgb.Booster(
        model_file=os.path.join(path, "component.txt"))


def prepare(frame: pd.DataFrame, meta: dict) -> pd.DataFrame:
    X = frame[meta["features"]].copy()
    for c in CATEGORICAL:
        X[c] = pd.Categorical(X[c].astype(str), categories=meta["categories"][c])
    return X


def embed(frame: pd.DataFrame, mean: pd.Series, std: pd.Series) -> np.ndarray:
    z = ((frame[SIGNATURE].astype(float) - mean) / std).fillna(0).to_numpy()
    norm = np.linalg.norm(z, axis=1, keepdims=True)
    return z / np.where(norm == 0, 1, norm)


def factor_value(v: object) -> float | str | None:
    if v is None or (isinstance(v, float) and np.isnan(v)):
        return None
    if isinstance(v, (int, float, np.integer, np.floating)):
        return round(float(v), 2)
    return str(v)   # categorical inputs such as the maker


def vec(v: np.ndarray) -> str:
    return "[" + ",".join(f"{x:.5f}" for x in v) + "]"


def main() -> None:
    cfg = data.Config()
    meta, risk, comp = load_active(cfg)
    daily, veh, bd = data.daily(cfg), data.vehicles(cfg), data.breakdowns(cfg)
    # The last complete day: the model was trained on whole days, and today's is still filling.
    today = pd.Timestamp(datetime.now(UTC).date())
    asof = daily.loc[daily["day"] < today, "day"].max()
    frame = features_asof(daily, veh, asof)
    X = prepare(frame, meta)

    raw = risk.predict(X)
    # Calibrated, but never certain: the top calibration bin was all failures, which says "very
    # likely", not "100%".
    prob = np.clip(np.interp(raw, meta["calibration"]["x"], meta["calibration"]["y"]), 0.001, 0.99)
    components = np.array(meta["components"])[comp.predict(X).argmax(axis=1)]
    costs = meta["costs"]
    saving = np.array([costs.get(c, {}).get("breakdown_cost_usd", 0) - costs.get(c, {}).get("repair_cost_usd", 0)
                       for c in components])

    # Per-vehicle explanation: the features pushing this vehicle's risk up the most.
    contrib = risk.predict(X, pred_contrib=True)[:, :-1]
    names = meta["features"]
    top = np.argsort(-contrib, axis=1)[:, :3]
    values = frame[names].to_numpy(dtype=object)

    scored_at = datetime.now(UTC)
    rows, alerts = [], []
    for i, r in enumerate(frame.itertuples(index=False)):
        factors = [{"feature": names[j], "label": factor_label(names[j]),
                    "value": factor_value(values[i, j]),
                    "contribution": round(float(contrib[i, j]), 3)}
                   for j in top[i] if contrib[i, j] > 0]
        rows.append((r.vehicle_id, scored_at, meta["version"], r.tenant_id, float(prob[i]), components[i],
                     round(float(saving[i]), 2), json.dumps(factors)))
        if prob[i] >= meta["threshold"]:
            alerts.append((r.tenant_id, r.vehicle_id, f"{r.vin}:HIGH_FAILURE_RISK:{asof.date()}", scored_at,
                           json.dumps({"probability": round(float(prob[i]), 3), "component": components[i],
                                       "est_cost_avoided_usd": round(float(saving[i]), 2),
                                       "model_version": meta["version"], "factors": factors,
                                       "severity": "CRITICAL"})))

    # Similar-case signatures: each past breakdown two days before it happened, and every vehicle
    # scored above the alert threshold now (outcome unknown). Standardised on today's fleet.
    mean, std = frame[SIGNATURE].astype(float).mean(), frame[SIGNATURE].astype(float).std().replace(0, 1)
    past = []
    for when, grp in bd.assign(day=bd["occurred_at"].dt.normalize() - pd.Timedelta(days=2)).groupby("day"):
        if when < daily["day"].min() + pd.Timedelta(days=6):
            continue
        f = label(features_asof(daily, veh, when), bd)
        f = f[f["vehicle_id"].isin(grp["vehicle_id"])].merge(grp[["vehicle_id", "repair_cost_usd"]], on="vehicle_id")
        for v, e in zip(f.itertuples(index=False), embed(f, mean, std), strict=True):
            past.append((v.vehicle_id, when + pd.Timedelta(days=1), vec(e), v.component, f"Repair {v.component}",
                         v.repair_cost_usd))
    risky = frame[prob >= meta["threshold"]]
    current = [(v.vehicle_id, scored_at, vec(e), None, None, None)
               for v, e in zip(risky.itertuples(index=False), embed(risky, mean, std), strict=True)]

    with psycopg.connect(cfg.postgres_dsn) as conn, conn.transaction():
        with conn.cursor().copy("""COPY risk_score (vehicle_id, scored_at, model_version, tenant_id, failure_prob_7d,
                                   predicted_component, est_cost_avoided_usd, top_factors) FROM STDIN""") as cp:
            for row in rows:
                cp.write_row(row)
        conn.cursor().executemany("""
            INSERT INTO alert (tenant_id, vehicle_id, rule_code, dedup_key, opened_at, details)
            VALUES (%s, %s, 'HIGH_FAILURE_RISK', %s, %s, %s::jsonb) ON CONFLICT (dedup_key) DO NOTHING""", alerts)
        conn.execute("DELETE FROM fault_signature")
        conn.cursor().executemany("""
            INSERT INTO fault_signature (vehicle_id, window_end, embedding, outcome_component, repair_action,
                                         repair_cost_usd) VALUES (%s, %s, %s::vector, %s, %s, %s)""", past + current)
    high = int((prob >= meta["threshold"]).sum())
    print(f"scored {len(rows):,} vehicles as of {asof.date()} with {meta['version']}: {high} above the alert threshold "
          f"({meta['threshold']:.2f}); {len(past)} past-failure and {len(current)} current signatures")


if __name__ == "__main__":
    main()
