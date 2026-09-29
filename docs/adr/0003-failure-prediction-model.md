# ADR 0003: Failure prediction as a calibrated 7-day classifier with a cost-based alert rule

**Status:** accepted, 2026-09-29

## Context

Fleet managers want to know which vehicles will break down soon, early enough to service them
first. Our inputs are daily per-vehicle telemetry rollups (21 days, 98,000 vehicles) and 3,669
observed breakdowns with their breakdown and planned-repair costs. Breakdowns affect about 1.2%
of vehicles in any 7-day window, so accuracy is meaningless. What matters is whether flagged
vehicles really fail, how many failures are caught, how early, and what it is worth.

## Decision

- **Task:** a binary classifier for a breakdown in the 7 days after an as-of day, one example per
  vehicle per as-of day. Features use only the 7 days up to that day: levels, 3-day-vs-prior
  trends, and days with each fault code. As-of days are limited to those with a fully observed
  label window, so there are no censored negatives. Tests guard against leakage and against the
  label boundaries shifting.
- **Model:** LightGBM (gradient-boosted trees). It handles missing values natively (EVs have no
  coolant), mixes categorical and numeric inputs, trains in about a minute, and gives exact
  per-feature contributions, which we show as each vehicle's "why".
- **Calibration:** isotonic regression on held-out validation vehicles, so "81%" means about 81%.
  Scores are capped at 99%: the top calibration bin was all failures, which is evidence of
  "very likely", not certainty.
- **Alerting:** raise `HIGH_FAILURE_RISK` when probability × average saving per caught breakdown
  exceeds the inspection cost, i.e. p > $150 / $902 = 16.6%. This is a decision rule a
  fleet manager can change by changing the cost, not a tuned threshold.
- **Evaluation:** train on 70% of vehicles and earlier days; calibrate and early-stop on 10%;
  report on the other 20% of vehicles over later days only. The baseline is a mechanic's rule
  (a serious fault code, over 100 °C, under 12 V, or battery health falling) compared at the
  **same number of vehicles flagged per day**.

## Results (test: unseen vehicles, later days)

| | Mechanic's rule | Model, same budget | Model, cost rule (p ≥ 16.6%) |
|---|---|---|---|
| Vehicles flagged per day (per ~19.6K) | 1,577 | 1,577 | 234 |
| Flagged vehicles that broke down | 8.0% | 11.9% | **77.8%** |
| Breakdowns caught | 53.9% | 80.1% | **78.0%** |
| Median warning | 2.1 days | 2.7 days | 2.7 days |
| Net saving per week per 100K vehicles | −$466K | −$303K | **+$712K** |

PR-AUC is 0.748 against a 0.012 base rate, and ROC-AUC is 0.89. The likely failing part is right
83% of the time. Calibration held on test: predictions of 80-100% came true 93.6% of the time.

At the rule's volume both approaches lose money, because inspecting 1,577 vehicles a day costs
more than the breakdowns it prevents. At the same volume the model still catches half as many
breakdowns again. Its real advantage is that the calibrated probability lets the fleet inspect only
where it pays.

**The inspection cost is the fleet's decision.** The test set's cost curve (flagged, caught and saved
per threshold) gives the net saving for any cost: $300 per inspection moves the threshold to 33%
and the saving to $542K a week, against $543.5K for the best threshold chosen in hindsight. The
cost rule stays within 1% of the best possible without being tuned on test data.

**A maker the model has never seen.** Leaving each maker out of training and calibration in turn:

| Maker | PR-AUC, trained with it | PR-AUC, never seen | Breakdowns caught, never seen | Alerts real, never seen |
|---|---|---|---|---|
| Aurora | 0.77 | 0.76 | 78% | 80% |
| Borealis | 0.70 | 0.69 | 71% | 83% |
| Cygnus | 0.77 | 0.77 | 80% | 82% |

The drop is at most 0.011 PR-AUC. The features are defined on the canonical event, not on any
maker's payload, so a newly onboarded maker (Draco in the demo) is scored from its first full day
with close to the accuracy of the makers the model was trained on.

## From scores to a workshop plan

Two products read the scores (`/v1/maintenance`):

- **Service plan.** Every vehicle with probability × cost avoided > inspection cost gets a bay:
  vehicles at 80% or more first and only tomorrow (median warning is 2.7 days), then the rest by
  expected saving, home depot first, then a depot within 350 km, earliest day. Urgent vehicles with no
  bay tomorrow are reported as too risky to wait instead of being pushed back. Greedy by value is
  simple to explain; an exact assignment could do slightly better when neighbouring depots are both
  full. Booking re-checks every item (tenant, not already booked, a free bay) in one transaction
  under a per-tenant lock, all or nothing. The copilot can propose the whole plan as one action.
- **Parts forecast.** Calibrated probabilities add up to expected failures: per depot and part,
  sum(p) with a 90% range from the variance sum(p(1-p)). Across Acme's fleet the forecast is 580
  breakdowns a week, in line with the history.

## Alternatives considered

- **Survival models (time to failure):** more natural for censored data, but the product needs
  "this week, yes or no, and how sure". A fixed-horizon classifier with fully observed windows
  answers that without censoring bias.
- **Sequence models over raw telemetry:** heavier to train and serve, and harder to explain. The
  daily rollups already hold the signal (fault-code frequency, voltage and health trends).
- **A precision-target threshold:** tried first. It selected 0.5% because the cumulative
  precision of the ranking stays high far down the list. That is a trap, replaced by the cost rule.

## Consequences

- Warnings arrive a median 2.7 days ahead. Most faults show clearly only in their last few days
  in this data, so "7 days" is the horizon, not the typical lead time. The dashboard says so.
- Scores are daily (as of the last complete day). A fault that appears within today reaches the
  live alert rules first and the model tomorrow.
- The data is simulated: the numbers show the method works on data with realistic structure (drift
  before failure, noise, benign codes, silent faults), not what a real fleet would save.
- Retraining is one command and registers the new version in `risk_model`; the API and
  dashboard read the active version.
