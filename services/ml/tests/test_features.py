import pandas as pd
import pytest

from fleetpulse_ml.data import DTC_CODES
from fleetpulse_ml.features import baseline_flags, features_asof, label

VIN = "AURMT4C20SC010846"
ASOF = pd.Timestamp("2026-09-20")


def day_row(day: str, coolant: float = 90.0, batt: float = 12.6, **codes: bool) -> dict:
    row = {"vin": VIN, "day": pd.Timestamp(day), "samples": 72, "km": 150.0, "coolant_avg": coolant,
           "coolant_max": coolant + 4, "batt_v_min": batt, "batt_v_avg": batt + 0.8, "soh_min": None, "rpm_avg": 1500.0,
           "dtc_events": sum(codes.values()), "harsh_events": 1, "idle_samples": 8}
    for c in DTC_CODES:
        row[f"dtc_{c}"] = codes.get(c, False)
    return row


@pytest.fixture
def vehicles() -> pd.DataFrame:
    return pd.DataFrame([{"vehicle_id": 1, "vin": VIN, "tenant_id": "t", "model_year": 2022,
                          "powertrain": "ICE", "oem": "AURORA"}])


def week(coolant_last3: float = 90.0) -> list[dict]:
    days = pd.date_range(ASOF - pd.Timedelta(days=6), ASOF)
    return [day_row(str(d.date()), coolant=coolant_last3 if d > ASOF - pd.Timedelta(days=3) else 90.0) for d in days]


def test_features_use_nothing_after_the_asof_day(vehicles):
    base = features_asof(pd.DataFrame(week()), vehicles, ASOF)
    with_future = features_asof(pd.DataFrame([*week(), day_row("2026-09-21", coolant=130, P0217=True)]), vehicles, ASOF)
    cols = [c for c in base.columns if c != "asof"]
    pd.testing.assert_frame_equal(base[cols], with_future[cols])


def test_trend_compares_the_last_three_days_with_the_four_before(vehicles):
    f = features_asof(pd.DataFrame(week(coolant_last3=99.0)), vehicles, ASOF).iloc[0]
    assert f["coolant_trend"] == pytest.approx(9.0)
    assert f["coolant_max_3d"] == pytest.approx(103.0)
    assert f["days_active_7d"] == 7
    assert f["age_years"] == 4


def test_fault_code_days_are_counted(vehicles):
    rows = week()
    rows[-1] = day_row(str(ASOF.date()), P0217=True)
    rows[-2] = day_row(str((ASOF - pd.Timedelta(days=1)).date()), P0217=True, P0420=True)
    f = features_asof(pd.DataFrame(rows), vehicles, ASOF).iloc[0]
    assert f["dtc_P0217_days_7d"] == 2
    assert f["dtc_P0420_days_7d"] == 1
    assert f["serious_dtc_days_3d"] == 2          # P0217 is serious, P0420 is not


@pytest.mark.parametrize(("when", "expected"), [
    ("2026-09-20 18:00", 0),   # during the as-of day: already happened, not a prediction
    ("2026-09-21 00:00", 1),   # first moment of the horizon
    ("2026-09-23 12:00", 1),
    ("2026-09-27 23:59", 1),   # last moment of the 7-day horizon
    ("2026-09-28 00:00", 0),   # 8th day: outside
])
def test_label_is_a_breakdown_in_the_seven_days_after_the_asof_day(vehicles, when, expected):
    frame = features_asof(pd.DataFrame(week()), vehicles, ASOF)
    bd = pd.DataFrame([{"vehicle_id": 1, "occurred_at": pd.Timestamp(when), "component": "Cooling system",
                        "breakdown_cost_usd": 2880.0, "repair_cost_usd": 1800.0}])
    out = label(frame, bd)
    assert out["label"].iloc[0] == expected
    if expected:
        assert out["component"].iloc[0] == "Cooling system"
        assert 0 <= out["days_to_breakdown"].iloc[0] < 7


def test_baseline_rule(vehicles):
    healthy = features_asof(pd.DataFrame(week()), vehicles, ASOF)
    hot = features_asof(pd.DataFrame(week(coolant_last3=98.0)), vehicles, ASOF)   # peak 102 C
    assert baseline_flags(healthy).iloc[0] == 0
    assert baseline_flags(hot).iloc[0] == 1


def test_identifiers_labels_and_the_split_are_never_features(vehicles):
    from fleetpulse_ml.features import feature_columns
    frame = features_asof(pd.DataFrame(week()), vehicles, ASOF).assign(group=3, label=0, component=None)
    cols = feature_columns(frame)
    assert not {"vin", "vehicle_id", "tenant_id", "asof", "group", "label", "component"} & set(cols)
    assert "coolant_trend" in cols and "powertrain" in cols
