# FleetPulse failure model

Predicts, for every vehicle, the probability of a breakdown in the next 7 days. It also predicts
the part most likely to fail and what servicing it now would save. The model is checked against a
rule a careful mechanic could apply without one.

```bash
uv sync
uv run python -m fleetpulse_ml.train   # builds features, trains, evaluates, registers the active model
uv run python -m fleetpulse_ml.score   # scores every vehicle; writes risk_score, alerts, similarity signatures
uv run python -m fleetpulse_ml.evaluate  # adds the cost curve and per-maker results to the active model
uv run pytest                          # feature, label, leakage and evaluation tests
```

Settings: `CLICKHOUSE_HOST/PORT/USER/PASSWORD`, `POSTGRES_DSN` (the `fleet_service` role) and
`MODEL_DIR` (default `models/`, not committed).

## Data

- **Signals:** the `vehicle_daily` rollup in ClickHouse, one row per vehicle per day of
  operation, over 21 days of history for 98,000 vehicles.
- **Labels:** `maintenance_event` breakdowns in Postgres, about 5% of vehicles over the history
  window. Each has a breakdown cost (repair plus towing and downtime) and a planned-repair cost.

## Examples, features, label

One example per vehicle per **as-of day**. The features use only that day and the six before it:

- **Levels:** peak coolant, lowest 12 V voltage, battery health, RPM, fault codes per day,
  harsh events per 100 km, idling share.
- **Trends:** the last 3 days minus the 4 before. A failing part changes over time; a vehicle
  that always runs warm does not.
- **Fault codes:** the number of days each code appeared. Benign background codes are
  included, so "any code" is not treated as a failure signal.
- **Vehicle facts:** powertrain, maker and age.

The **label** is 1 when the vehicle breaks down in the 7 days after the as-of day. As-of days are
limited to those with a full week of history behind them *and* a fully observed week after them.
Otherwise a breakdown after the end of the data would be counted, wrongly, as "no breakdown".
`tests/test_features.py` checks the label boundaries and that no data after the as-of day leaks
into the features.

## Split and evaluation

| Set | Vehicles | As-of days | Used for |
|---|---|---|---|
| train | 70% | earlier | fitting |
| valid | 10% | earlier | early stopping, probability calibration (isotonic), alert threshold |
| test | 20% | later | every number reported |

The test set contains only vehicles and days the model never saw. The baseline flags a vehicle
that, in the last 3 days, reported a severity 4-5 fault code, ran above 100 °C, dropped below
12 V, or lost more than 2 points of battery health in a week. For a fair comparison, the model
flags **the same number of vehicles per day** as the rule, taking the highest-risk ones.

Money: each breakdown caught saves its breakdown cost minus the planned-repair cost, both
averaged per component from the maintenance history. Each flagged vehicle costs an inspection
(an assumption: $150).

Beyond the headline numbers, training (and `evaluate`, for a model trained before them) stores:

- **Cost curve:** for each probability threshold, vehicles flagged, breakdowns caught and money
  saved per 100K vehicles. Any inspection cost then gives its net saving without retraining; the
  dashboard's slider reads it.
- **Per maker:** results for each maker's vehicles.
- **Never-seen maker:** each maker is left out of training and calibration in turn and the model is
  tested on it. This is the onboarding case: a new maker's vehicles arrive in the canonical format
  and are scored by a model that has never seen that maker.

## Results

See the latest training run's `models/<version>/meta.json`, the `risk_model.metrics` column of the
active model, and the dashboard's **At risk** page.
