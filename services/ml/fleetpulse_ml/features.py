"""Feature table: one row per (vehicle, as-of day), built only from data up to that day.

Faults develop over 3-10 days before a breakdown, so the features describe the last week:
levels (peak coolant, lowest 12 V), trends (last 3 days against the 4 before, which is what
separates a failing part from a vehicle that always runs warm), and how often each fault code
appeared. Nothing after the as-of day is used, so the same code scores today's fleet.
"""

import numpy as np
import pandas as pd

from .data import DTC_CODES

HORIZON_DAYS = 7
LOOKBACK_DAYS = 7

# Severity 4-5 codes in the dtc_code reference table: what a mechanic's rule would watch.
SERIOUS_CODES = ["P0300", "P0217", "P0700", "P0A80", "P0AFA", "P0AA6", "C0265", "U0100"]

CATEGORICAL = ["powertrain", "oem"]


def _window(daily: pd.DataFrame, asof: pd.Timestamp, days: int) -> pd.DataFrame:
    return daily[(daily["day"] > asof - pd.Timedelta(days=days)) & (daily["day"] <= asof)]


def features_asof(daily: pd.DataFrame, vehicles: pd.DataFrame, asof: pd.Timestamp) -> pd.DataFrame:
    """Features for every vehicle that operated in the week up to and including ``asof``."""
    w7 = _window(daily, asof, 7)
    w3 = _window(daily, asof, 3)
    prior = w7[w7["day"] <= asof - pd.Timedelta(days=3)]   # the 4 days before the last 3
    last = w7[w7["day"] == asof]
    code_cols = [f"dtc_{c}" for c in DTC_CODES]

    g7 = w7.groupby("vin")
    f = pd.DataFrame({
        "days_active_7d": g7["day"].count(),
        "km_7d": g7["km"].sum(),
        "coolant_max_7d": g7["coolant_max"].max(),
        "coolant_avg_7d": g7["coolant_avg"].mean(),
        "batt_min_7d": g7["batt_v_min"].min(),
        "batt_avg_7d": g7["batt_v_avg"].mean(),
        "soh_min_7d": g7["soh_min"].min(),
        "soh_max_7d": g7["soh_min"].max(),
        "rpm_avg_7d": g7["rpm_avg"].mean(),
        "dtc_events_7d": g7["dtc_events"].sum(),
        "harsh_7d": g7["harsh_events"].sum(),
        "idle_ratio_7d": g7["idle_samples"].sum() / g7["samples"].sum().clip(lower=1),
    })
    f["soh_drop_7d"] = f["soh_max_7d"] - f["soh_min_7d"]
    for c in code_cols:
        f[f"{c}_days_7d"] = g7[c].sum()

    g3 = w3.groupby("vin")
    f3 = pd.DataFrame({
        "coolant_max_3d": g3["coolant_max"].max(),
        "coolant_avg_3d": g3["coolant_avg"].mean(),
        "batt_min_3d": g3["batt_v_min"].min(),
        "batt_avg_3d": g3["batt_v_avg"].mean(),
        "rpm_avg_3d": g3["rpm_avg"].mean(),
        "dtc_events_3d": g3["dtc_events"].sum(),
        "harsh_3d": g3["harsh_events"].sum(),
        "km_3d": g3["km"].sum(),
        "serious_dtc_days_3d": g3[[f"dtc_{c}" for c in SERIOUS_CODES]].sum().sum(axis=1),
    })
    gp = prior.groupby("vin")
    fp = pd.DataFrame({
        "coolant_avg_prior": gp["coolant_avg"].mean(),
        "batt_avg_prior": gp["batt_v_avg"].mean(),
        "rpm_avg_prior": gp["rpm_avg"].mean(),
        "harsh_prior": gp["harsh_events"].sum(),
        "km_prior": gp["km"].sum(),
    })
    fl = last.set_index("vin")[["coolant_max", "batt_v_min", "dtc_events"]].add_suffix("_1d")

    f = f.join(f3).join(fp).join(fl)
    # Trends: a failing part changes; a vehicle that always runs warm does not.
    f["coolant_trend"] = f["coolant_avg_3d"] - f["coolant_avg_prior"]
    f["batt_trend"] = f["batt_avg_3d"] - f["batt_avg_prior"]
    f["rpm_trend"] = f["rpm_avg_3d"] - f["rpm_avg_prior"]
    f["harsh_per_100km_3d"] = 100 * f["harsh_3d"] / f["km_3d"].clip(lower=1)
    f["harsh_per_100km_prior"] = 100 * f["harsh_prior"] / f["km_prior"].clip(lower=1)
    f["harsh_trend"] = f["harsh_per_100km_3d"] - f["harsh_per_100km_prior"]
    f = f.drop(columns=["soh_max_7d", "coolant_avg_prior", "batt_avg_prior", "rpm_avg_prior",
                        "harsh_prior", "km_prior"])

    f = f.reset_index().merge(vehicles, on="vin", how="inner")
    f["age_years"] = asof.year - f["model_year"]
    f["asof"] = asof
    for c in CATEGORICAL:
        f[c] = f[c].astype("category")
    return f


def feature_columns(frame: pd.DataFrame) -> list[str]:
    # Identifiers, labels, outcomes and the train/test split must never be inputs.
    skip = {"vin", "vehicle_id", "tenant_id", "model_year", "asof", "label", "component", "days_to_breakdown",
            "breakdown_cost_usd", "repair_cost_usd", "occurred_at", "group"}
    return [c for c in frame.columns if c not in skip]


def label(frame: pd.DataFrame, breakdowns: pd.DataFrame) -> pd.DataFrame:
    """Adds ``label`` (breakdown in the 7 days after the as-of day), the component and costs."""
    window_start = frame["asof"] + pd.Timedelta(days=1)   # the as-of day is complete at midnight
    m = frame[["vehicle_id"]].assign(start=window_start, row=np.arange(len(frame))).merge(
        breakdowns, on="vehicle_id", how="left")
    hit = (m["occurred_at"] >= m["start"]) & (m["occurred_at"] < m["start"] + pd.Timedelta(days=HORIZON_DAYS))
    first = m[hit].sort_values("occurred_at").drop_duplicates("row").set_index("row")
    out = frame.copy()
    out["label"] = 0
    out.loc[first.index, "label"] = 1
    out["component"] = first["component"].reindex(range(len(frame))).to_numpy()
    out["days_to_breakdown"] = ((first["occurred_at"] - first["start"]).dt.total_seconds() / 86_400).reindex(
        range(len(frame))).to_numpy()
    return out


def baseline_flags(frame: pd.DataFrame) -> pd.Series:
    """What a careful mechanic might do without a model: flag any vehicle that, in the last three
    days, reported a serious fault code, ran above 100 C, dropped below 12 V, or lost more than
    2 points of battery health in a week."""
    return ((frame["serious_dtc_days_3d"] > 0)
            | (frame["coolant_max_3d"] > 100)
            | (frame["batt_min_3d"] < 12.0)
            | (frame["soh_drop_7d"] > 2)).astype(int)
