import numpy as np
import pandas as pd
import pytest

from fleetpulse_ml.evaluation import by_maker, cost_curve


@pytest.fixture
def test_rows() -> pd.DataFrame:
    # Two weekly checks of a 4-vehicle fleet; one breakdown each week, saving $900 and $300.
    return pd.DataFrame({
        "asof": pd.to_datetime(["2026-09-19"] * 4 + ["2026-09-20"] * 4),
        "oem": pd.Categorical(["AURORA", "AURORA", "CYGNUS", "CYGNUS"] * 2),
        "label": [1, 0, 0, 0, 0, 0, 1, 0],
        "breakdown_cost_usd": [1000.0, None, None, None, None, None, 500.0, None],
        "repair_cost_usd": [100.0, None, None, None, None, None, 200.0, None],
    })


def point(curve, t):
    return next(c for c in curve if c["threshold"] == t)


def test_cost_curve_counts_flags_catches_and_savings_per_100k(test_rows):
    p = np.array([0.9, 0.3, 0.05, 0.0, 0.0, 0.3, 0.4, 0.0])
    curve = cost_curve(test_rows, p)
    scale = 100_000 / 4            # a 4-vehicle fleet, scaled to 100K
    at_025 = point(curve, 0.25)    # flags 0.9, 0.3 | 0.3, 0.4: 4 flags over 2 days, both breakdowns caught
    assert at_025["flagged"] == pytest.approx(2 * scale)
    assert at_025["caught"] == pytest.approx(1 * scale)
    assert at_025["saved_usd"] == pytest.approx((900 + 300) / 2 * scale)
    assert at_025["precision"] == 0.5 and at_025["recall"] == 1.0
    at_05 = point(curve, 0.5)      # only the 0.9: one catch worth $900 over 2 days
    assert at_05["saved_usd"] == pytest.approx(900 / 2 * scale)
    assert at_05["recall"] == 0.5
    # Net saving for any inspection cost comes straight from the curve.
    assert at_05["saved_usd"] - 150 * at_05["flagged"] > at_025["saved_usd"] - 150 * at_025["flagged"]


def test_cost_curve_thresholds_increase_and_flags_never_grow(test_rows):
    curve = cost_curve(test_rows, np.linspace(0, 1, 8))
    ts = [c["threshold"] for c in curve]
    assert ts == sorted(ts)
    flags = [c["flagged"] for c in curve]
    assert all(a >= b for a, b in zip(flags, flags[1:], strict=False))


def test_by_maker_splits_results(test_rows):
    p = np.array([0.9, 0.3, 0.05, 0.0, 0.0, 0.3, 0.4, 0.0])
    rows = {r["maker"]: r for r in by_maker(test_rows, p, threshold=0.35)}
    assert rows["AURORA"]["recall"] == 1.0 and rows["AURORA"]["precision"] == 1.0
    assert rows["CYGNUS"]["recall"] == 1.0 and rows["CYGNUS"]["examples"] == 4
