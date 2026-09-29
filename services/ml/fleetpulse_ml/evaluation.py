"""Evaluation beyond one headline number: what each inspection cost would mean, how the model
does for each vehicle maker, and how it does for a maker it has never seen (the onboarding case).
Every function works on the held-out test rows and their calibrated probabilities."""

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import average_precision_score

from .features import CATEGORICAL

# Probability thresholds for the cost curve: fine where alerting decisions are made, coarse above.
CURVE_THRESHOLDS = np.round(np.concatenate([np.arange(0.01, 0.10, 0.01), np.arange(0.10, 0.96, 0.025)]), 3)

MODEL_PARAMS = {"n_estimators": 3000, "learning_rate": 0.03, "num_leaves": 31, "min_child_samples": 50,
                "subsample": 0.8, "subsample_freq": 1, "colsample_bytree": 0.8, "reg_lambda": 1.0,
                "verbose": -1, "random_state": 7}


def scale_per_100k(frame: pd.DataFrame) -> tuple[int, float]:
    """(as-of days, factor that turns per-day totals into per-day totals for a 100K fleet)."""
    days = frame["asof"].nunique()
    return days, 100_000 / (len(frame) / days) if len(frame) else 0.0


def cost_curve(test: pd.DataFrame, p: np.ndarray) -> list[dict]:
    """For each threshold: vehicles flagged, breakdowns caught and money those catches save, per
    weekly check of a 100K fleet. With these, any inspection cost C gives net = saved - C x flagged,
    so the dashboard can answer "what if an inspection costs $300?" without retraining."""
    days, scale = scale_per_100k(test)
    y = test["label"].to_numpy()
    saving = (test["breakdown_cost_usd"] - test["repair_cost_usd"]).fillna(0).to_numpy()
    positives = int(y.sum())
    out = []
    for t in CURVE_THRESHOLDS:
        flag = p >= t
        caught = flag & (y == 1)
        out.append({"threshold": float(t),
                    "flagged": round(float(flag.sum()) / days * scale, 1),
                    "caught": round(float(caught.sum()) / days * scale, 1),
                    "saved_usd": round(float(saving[caught].sum()) / days * scale),
                    "precision": round(float(caught.sum() / flag.sum()), 3) if flag.any() else None,
                    "recall": round(float(caught.sum() / positives), 3) if positives else 0.0})
    return out


def _at_threshold(y: np.ndarray, p: np.ndarray, threshold: float) -> dict:
    flag = p >= threshold
    tp = int((flag & (y == 1)).sum())
    return {"pr_auc": round(float(average_precision_score(y, p)), 3) if 0 < y.sum() < len(y) else None,
            "precision": round(tp / int(flag.sum()), 3) if flag.any() else None,
            "recall": round(tp / int(y.sum()), 3) if y.sum() else None}


def by_maker(test: pd.DataFrame, p: np.ndarray, threshold: float) -> list[dict]:
    """The full model's results for each maker's vehicles in the test set."""
    out = []
    for maker, idx in test.groupby("oem", observed=True).indices.items():
        y = test["label"].to_numpy()[idx]
        out.append({"maker": str(maker), "examples": len(idx), "breakdown_rate": round(float(y.mean()), 4),
                    **_at_threshold(y, p[idx], threshold)})
    return out


def fit_calibrated(train: pd.DataFrame, valid: pd.DataFrame, X: list[str]):
    """The production recipe: gradient-boosted trees, early-stopped and calibrated on validation."""
    model = lgb.LGBMClassifier(**MODEL_PARAMS)
    model.fit(train[X], train["label"], eval_X=valid[X], eval_y=valid["label"], eval_metric="average_precision",
              categorical_feature=CATEGORICAL, callbacks=[lgb.early_stopping(150, verbose=False)])
    calib = IsotonicRegression(out_of_bounds="clip", y_min=0, y_max=1).fit(
        model.predict_proba(valid[X])[:, 1], valid["label"])
    return model, calib


def unseen_maker(train: pd.DataFrame, valid: pd.DataFrame, test: pd.DataFrame, X: list[str],
                 p_full: np.ndarray, threshold: float) -> list[dict]:
    """Leave one maker out: train and calibrate without any of its vehicles, then test on them.
    This is what happens the day a new maker is onboarded: its vehicles arrive in the canonical
    format and are scored by a model that has never seen that maker."""
    out = []
    for maker in sorted(test["oem"].dropna().unique()):
        model, calib = fit_calibrated(train[train["oem"] != maker], valid[valid["oem"] != maker], X)
        mask = (test["oem"] == maker).to_numpy()
        held = test[mask]
        y = held["label"].to_numpy()
        p_unseen = calib.predict(model.predict_proba(held[X])[:, 1])
        out.append({"maker": str(maker), "examples": int(mask.sum()),
                    "trained_with": _at_threshold(y, p_full[mask], threshold),
                    "never_seen": _at_threshold(y, p_unseen, threshold)})
    return out
