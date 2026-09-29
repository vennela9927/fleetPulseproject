"""Re-evaluates the active model on its own test split and adds the cost curve and per-maker
results to its registered metrics. Rebuilds the same dataset and split as training, so the
existing numbers are reproduced (and checked) rather than changed."""

import json
import os

import numpy as np
import psycopg
from sklearn.metrics import average_precision_score

from . import data
from .features import feature_columns
from .score import load_active, prepare
from .train import build_dataset, extra_metrics, split


def main() -> None:
    cfg = data.Config()
    meta, risk, _ = load_active(cfg)
    ds, asofs = build_dataset(cfg)
    X = feature_columns(ds)
    if X != meta["features"]:
        raise SystemExit("the data no longer produces the features this model was trained on; retrain instead")
    train, valid, test, _ = split(ds, asofs)
    p_test = np.interp(risk.predict(prepare(test, meta)), meta["calibration"]["x"], meta["calibration"]["y"])

    # The same model on the same rows must reproduce the registered headline number.
    pr_auc = round(float(average_precision_score(test["label"], p_test)), 3)
    registered = meta["metrics"]["model"]["pr_auc"]
    if abs(pr_auc - registered) > 0.002:
        raise SystemExit(f"test PR-AUC {pr_auc} differs from the registered {registered}: the data has changed")
    print(f"reproduced PR-AUC {pr_auc} on {len(test):,} test rows; computing cost curve and per-maker results")

    extra = extra_metrics(train, valid, test, X, p_test, meta["threshold"])
    meta["metrics"].update(extra)
    with open(os.path.join(cfg.model_dir, meta["version"], "meta.json"), "w") as fh:
        json.dump(meta, fh, indent=2)
    with psycopg.connect(cfg.postgres_dsn) as conn, conn.transaction():
        conn.execute("UPDATE risk_model SET metrics = metrics || %s::jsonb WHERE version = %s",
                     (json.dumps(extra), meta["version"]))
    print(json.dumps({k: v for k, v in extra.items() if k != "cost_curve"}, indent=2))


if __name__ == "__main__":
    main()
