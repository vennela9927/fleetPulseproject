"""Trains and evaluates the 7-day breakdown model against a rule-based baseline.

Split, so the test answers "does it work on vehicles and days it has never seen":
  train: 70% of vehicles, earlier as-of days     (fit)
  valid: 10% of vehicles, earlier as-of days     (early stopping, probability calibration, alert threshold)
  test:  20% of vehicles, later as-of days       (every number reported)
"""

import hashlib
import json
import os
from datetime import UTC, datetime

import lightgbm as lgb
import numpy as np
import pandas as pd
import psycopg
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score

from . import data, evaluation
from .features import CATEGORICAL, HORIZON_DAYS, LOOKBACK_DAYS, baseline_flags, feature_columns, features_asof, label

INSPECTION_COST_USD = 150.0   # assumption: a technician's check of a flagged vehicle


def vehicle_group(vin: str) -> int:
    return int(hashlib.sha256(vin.encode()).hexdigest(), 16) % 10


def build_dataset(cfg: data.Config) -> tuple[pd.DataFrame, list[pd.Timestamp]]:
    daily, veh, bd = data.daily(cfg), data.vehicles(cfg), data.breakdowns(cfg)
    # Whole days only: today is still filling, and breakdown labels exist only for past days.
    daily = daily[daily["day"] < pd.Timestamp(datetime.now(UTC).date())]
    first, end = daily["day"].min(), daily["day"].max() + pd.Timedelta(days=1)
    # Full lookback behind every as-of day, and a fully observed 7-day label window after it.
    asofs = list(pd.date_range(first + pd.Timedelta(days=LOOKBACK_DAYS - 1),
                               end - pd.Timedelta(days=HORIZON_DAYS + 1)))
    if len(asofs) < 4:
        raise SystemExit(f"need at least {LOOKBACK_DAYS + HORIZON_DAYS + 3} days of history, have {(end - first).days}")
    frames = [label(features_asof(daily, veh, d), bd) for d in asofs]
    ds = pd.concat(frames, ignore_index=True)
    for c in CATEGORICAL:
        ds[c] = ds[c].astype("category")
    ds["group"] = ds["vin"].map(vehicle_group)
    costs = bd.groupby("component")[["breakdown_cost_usd", "repair_cost_usd"]].mean()
    ds = ds.join(costs, on="component")
    return ds, asofs


def at_budget(frame: pd.DataFrame, scores: np.ndarray, budget: pd.Series) -> pd.Series:
    """Flags the top-scored vehicles each day, as many as the baseline flagged that day."""
    ranked = frame.assign(score=scores).sort_values("score", ascending=False)
    ranked["rank"] = ranked.groupby("asof").cumcount()
    return (ranked["rank"] < ranked["asof"].map(budget)).astype(int).reindex(frame.index)


def outcome(frame: pd.DataFrame, flagged: pd.Series, fleet_size: int) -> dict:
    """Precision, recall, lead time and money for one flagging policy, per as-of day, then
    averaged and scaled to a 100K-vehicle fleet (each as-of day is one weekly check)."""
    f = frame.assign(flag=flagged)
    tp = f[(f["flag"] == 1) & (f["label"] == 1)]
    n_flag, n_pos = int(f["flag"].sum()), int(f["label"].sum())
    saved = (tp["breakdown_cost_usd"] - tp["repair_cost_usd"]).sum()
    spent = n_flag * INSPECTION_COST_USD
    days = f["asof"].nunique()
    scale = 100_000 / (fleet_size / days) if fleet_size else 0
    return {
        "flagged_per_day": round(n_flag / days, 1),
        "precision": round(len(tp) / n_flag, 3) if n_flag else 0.0,
        "recall": round(len(tp) / n_pos, 3) if n_pos else 0.0,
        "median_days_warning": round(float(tp["days_to_breakdown"].median()), 1) if len(tp) else None,
        "net_savings_usd_per_week_per_100k": round((saved - spent) / days * scale),
    }


def split(ds: pd.DataFrame, asofs: list[pd.Timestamp]) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame,
                                                                  pd.Timestamp]:
    cut = asofs[len(asofs) * 5 // 8]
    train = ds[(ds["group"] < 7) & (ds["asof"] < cut)]
    valid = ds[(ds["group"] == 7) & (ds["asof"] < cut)]
    test = ds[(ds["group"] >= 8) & (ds["asof"] >= cut)]
    return train, valid, test, cut


def average_saving(ds: pd.DataFrame) -> float:
    caught = ds[ds["label"] == 1].drop_duplicates("vehicle_id")
    return float((caught["breakdown_cost_usd"] - caught["repair_cost_usd"]).mean())


def extra_metrics(train: pd.DataFrame, valid: pd.DataFrame, test: pd.DataFrame, X: list[str],
                  p_test: np.ndarray, threshold: float) -> dict:
    return {"cost_curve": evaluation.cost_curve(test, p_test),
            "by_maker": evaluation.by_maker(test, p_test, threshold),
            "unseen_maker": evaluation.unseen_maker(train, valid, test, X, p_test, threshold)}


def main() -> None:
    cfg = data.Config()
    ds, asofs = build_dataset(cfg)
    X = feature_columns(ds)
    train, valid, test, cut = split(ds, asofs)
    print(f"examples: train {len(train):,} ({train['label'].mean():.2%} positive), valid {len(valid):,}, "
          f"test {len(test):,} ({test['label'].mean():.2%} positive); as-of days {asofs[0].date()}..{asofs[-1].date()}")

    model, calib = evaluation.fit_calibrated(train, valid, X)
    p_test = calib.predict(model.predict_proba(test[X])[:, 1])

    # Alert when servicing pays for itself on average: calibrated probability x average saving
    # per caught breakdown > the inspection cost. A decision rule, not a tuned number.
    avg_saving = average_saving(ds)
    threshold = INSPECTION_COST_USD / avg_saving

    base = baseline_flags(test)
    budget = base.groupby(test["asof"]).sum()
    model_at_budget = at_budget(test, p_test, budget)
    model_at_threshold = (p_test >= threshold).astype(int)
    fleet_rows = len(test)

    # Likely component, trained on the positives only.
    pos_train, pos_test = train[train["label"] == 1], test[test["label"] == 1]
    comp = lgb.LGBMClassifier(n_estimators=300, learning_rate=0.05, num_leaves=15, min_child_samples=20,
                              verbose=-1, random_state=7)
    comp.fit(pos_train[X], pos_train["component"], categorical_feature=CATEGORICAL)
    comp_acc = float((comp.predict(pos_test[X]) == pos_test["component"]).mean())

    importance = sorted(zip(X, model.booster_.feature_importance("gain"), strict=True), key=lambda t: -t[1])[:10]
    bins = pd.cut(p_test, [0, 0.05, 0.2, 0.5, 0.8, 1.0], include_lowest=True)
    reliability = (pd.DataFrame({"bin": bins, "p": p_test, "y": test["label"].to_numpy()})
                   .groupby("bin", observed=True).agg(predicted=("p", "mean"), observed=("y", "mean"), n=("y", "size")))
    metrics = {
        "horizon_days": HORIZON_DAYS,
        "split": {"train": len(train), "valid": len(valid), "test": len(test),
                  "test_positive_rate": round(float(test["label"].mean()), 4),
                  "asof_days": [str(asofs[0].date()), str(asofs[-1].date())], "test_from": str(cut.date())},
        "model": {"pr_auc": round(float(average_precision_score(test["label"], p_test)), 3),
                  "roc_auc": round(float(roc_auc_score(test["label"], p_test)), 3),
                  "brier": round(float(brier_score_loss(test["label"], p_test)), 4),
                  "trees": int(model.best_iteration_ or model.n_estimators)},
        "baseline_rule": outcome(test, base, fleet_rows),
        "model_same_budget": outcome(test, model_at_budget, fleet_rows),
        "model_alert_threshold": {"threshold": round(threshold, 3), "avg_saving_usd": round(avg_saving),
                                  **outcome(test, model_at_threshold, fleet_rows)},
        "component_accuracy": round(comp_acc, 3),
        "top_features": [{"feature": f, "gain": round(float(g))} for f, g in importance],
        "reliability": [{"bin": str(b), "predicted": round(float(r.predicted), 3),
                         "observed": round(float(r.observed), 3), "n": int(r.n)}
                        for b, r in reliability.iterrows()],
        "assumptions": {"inspection_cost_usd": INSPECTION_COST_USD,
                        "saving_per_caught_breakdown": "breakdown cost minus planned repair cost, per component, "
                                                       "from the maintenance history"},
        **extra_metrics(train, valid, test, X, p_test, threshold),
    }

    version = f"lgbm-{datetime.now(UTC):%Y%m%d-%H%M}"
    out = os.path.join(cfg.model_dir, version)
    os.makedirs(out, exist_ok=True)
    model.booster_.save_model(os.path.join(out, "risk.txt"))
    comp.booster_.save_model(os.path.join(out, "component.txt"))
    with open(os.path.join(out, "meta.json"), "w") as fh:
        json.dump({"version": version, "features": X,
                   "categories": {c: list(ds[c].cat.categories) for c in CATEGORICAL},
                   "components": list(comp.classes_), "threshold": threshold,
                   "calibration": {"x": calib.X_thresholds_.tolist(), "y": calib.y_thresholds_.tolist()},
                   "costs": ds.dropna(subset=["component"]).groupby("component")[
                       ["breakdown_cost_usd", "repair_cost_usd"]].mean().round(2).to_dict("index"),
                   "metrics": metrics}, fh, indent=2)
    with psycopg.connect(cfg.postgres_dsn) as conn, conn.transaction():
        conn.execute("UPDATE risk_model SET is_active = false WHERE is_active")
        conn.execute("INSERT INTO risk_model (version, trained_at, metrics, is_active) VALUES (%s, now(), %s, true)",
                     (version, json.dumps(metrics)))
    print(json.dumps(metrics, indent=2))
    print(f"saved {out} and registered {version} as the active model")


if __name__ == "__main__":
    main()
