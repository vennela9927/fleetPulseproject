import numpy as np

from fleetpulse_ml.drift import hint, psi


def test_same_distribution_is_stable():
    rng = np.random.default_rng(1)
    assert psi(rng.normal(40, 15, 5000), rng.normal(40, 15, 5000)) < 0.05


def test_mph_labelled_kmh_is_drift_with_a_hint():
    rng = np.random.default_rng(2)
    kmh = np.clip(rng.normal(45, 15, 5000), 0, None)
    mph = kmh / 1.609344
    assert psi(kmh, mph) > 0.25
    assert "mph" in hint("speed_kmh", kmh.mean(), mph.mean())


def test_many_ties_do_not_break_the_bins():
    # Parked vehicles report exactly 0 km/h: most deciles collapse onto one edge.
    base = np.concatenate([np.zeros(4000), np.full(1000, 50.0)])
    assert psi(base, base.copy()) < 0.01
    assert psi(base, np.concatenate([np.zeros(1000), np.full(4000, 50.0)])) > 0.25


def test_no_hint_for_an_ordinary_shift():
    assert hint("batt_v", 12.6, 12.1) is None
    assert "Fahrenheit" in hint("coolant_c", 90, 194)


def test_a_shift_across_every_maker_is_not_blamed_on_one_feed():
    import pandas as pd

    from fleetpulse_ml.drift import classify
    rows = pd.DataFrame([{"oem_code": m, "field": "coolant_c", "psi": p, "baseline_mean": 90.0, "current_mean": 70.0,
                          "current_n": 1000} for m, p in [("AURORA", 0.42), ("BOREALIS", 0.41), ("CYGNUS", 0.45)]]
                        + [{"oem_code": m, "field": "speed_kmh", "psi": p, "baseline_mean": 40.0, "current_mean": b,
                            "current_n": 1000} for m, p, b in [("AURORA", 1.3, 24.9), ("BOREALIS", 0.02, 40.0),
                                                                ("CYGNUS", 0.03, 40.1)]])
    out = classify(rows).set_index(["oem_code", "field"])
    assert set(out.xs("coolant_c", level="field")["status"]) == {"OK"}
    assert out.loc[("AURORA", "speed_kmh"), "status"] == "DRIFT"
    assert "mph" in out.loc[("AURORA", "speed_kmh"), "hint"]
    assert out.loc[("BOREALIS", "speed_kmh"), "status"] == "OK"


def test_the_unit_hint_divides_out_a_fleet_wide_change():
    # Everyone slowed to a sixth (night-time); Aurora slowed to a sixth AND switched to mph.
    assert hint("speed_kmh", 25.0, 25.0 / 6 / 1.609344, fleet_ratio=1 / 6) is not None
    assert hint("speed_kmh", 25.0, 25.0 / 6, fleet_ratio=1 / 6) is None
