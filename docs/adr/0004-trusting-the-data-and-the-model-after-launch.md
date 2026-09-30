# ADR 0004: Trusting the data and the model after launch

**Status:** accepted, 2026-09-30

## Context

A failure model is only as good as its inputs and only as trustworthy as its latest check. Three
things go wrong in production that a test set cannot show:

1. **A sensor fails, not the part.** An erratic coolant sensor looks like an overheating engine:
   an alert, a workshop slot, and nothing found.
2. **A feed changes without breaking.** A firmware update sends speed in mph but still labels it
   km/h. Every value passes validation and no alert fires, but trip distances, harsh-driving rates
   and the model's inputs are all quietly wrong.
3. **The world moves away from the training data.** The model's probabilities were calibrated on
   history. Nothing tells you when they stop being true.

## Decision

**Sensor plausibility in the live rules (stream processor).** Coolant has thermal mass: it cannot
change by more than 25 °C between readings seconds apart. Three such jumps within five minutes
raise `SENSOR_FAULT` ("check the sensor, not the engine"), and overheat alerts from that sensor are
held back for 30 minutes. A real overheat climbs smoothly and is unaffected (unit tests cover both,
and a spread of isolated odd readings that must not trigger it).

**Feed drift per maker (batch job, `fleetpulse-drift`, every 2 minutes).** Each maker's last 5
minutes are compared with the 40 minutes before, field by field, with the population stability
index (PSI). Speed and rpm are compared only while vehicles move: how many are parked or charging
changes through the day, differently per maker, and that is the fleet operating, not the feed
changing. A maker is judged only when its history is continuous (data in at least 90% of those
minutes), so after a restart or an outage the check stays silent for up to an hour instead of
comparing against a gap. Changes that move every maker together are divided out: a maker is flagged
only when its PSI exceeds the median of the other makers by more than 0.25. The same relative view
names likely causes: a moving-speed mean at about 0.62 times its usual level looks like mph sent as
km/h. Results go to `feed_drift` and the Vehicle makers page.

**The model graded by the workshop (API).** Each booking stores the probability and part the model
gave when it was booked. When a mechanic records the result, the booking becomes a label. Over the
last 30 days: faults found vs the sum of promised probabilities, with a range of two standard
deviations of sum(p(1-p)). Well below the range means the probabilities are overconfident for
today's fleet: recalibrate or retrain.

## What we observed (simulated data, 2026-09-30)

- **Sensor faults:** coolant sensors broken on 3 vehicles were flagged within 20 seconds each (three
  jumps), with no overheat alert raised for them.
- **Feed drift:** with the Aurora firmware bug on, the check flagged Aurora speed (PSI 1.67) and
  read it as "looks like mph sent as km/h", while the other makers' speed, which had also shifted
  after a simulator restart, was read as "every maker shifted together". Before the bug, and
  before the relative rule, every maker was flagged after the restart: the rule was changed because
  of that false alarm. The relative rule was not enough on its own: with a 6-hour baseline spanning
  several restarts and a backlog, Borealis and Draco rpm and battery voltage were flagged with no
  bug injected (makers shift differently after a restart: EVs, diesel trucks). The baseline was
  shortened to 40 minutes and a maker with a gap in it is no longer judged. That still flagged speed
  and rpm for four makers with no bug, the parked share settling differently per maker; and with a
  10-minute window the real bug was flagged only 7 minutes in. Final design, live run 12:11-12:30
  UTC: three checks "all stable" with no bug; bug on at 12:13:48, Aurora speed WATCH after 2 min 17 s
  and DRIFT after 4 min 40 s (PSI 1.35, then 2.53), nothing else flagged; after the fix, WATCH at
  PSI 0.16-0.22 while the bug period is still inside the baseline. The moving-speed mean went from
  44.3 to 29.5 km/h (0.665x), outside the unit hint's first band (0.58-0.66), which was widened.
  Repeated at 12:37 with the wider band: WATCH after 2 min 15 s, DRIFT after 4 min 37 s, and at
  7 minutes, with the window full of bug data, "about 0.62x its usual level: looks like mph sent
  as km/h".
- **Workshop feedback:** 66 inspections with ground truth from the simulator found faults in 86%,
  against 96% promised (range 91-100%): below expected. Most were scored at the 99% cap, while the
  test set's top bucket came true 94% of the time. The loop caught the cap as overconfident, which is
  the kind of finding it exists for.

## Consequences

- The sensor rule covers coolant only; the same pattern (physical rate limits per signal) extends to
  battery voltage and state of charge.
- Drift needs at least three makers reporting and a few hundred readings per field; a new maker is
  judged once it has a few hours of its own history.
- Workshop results are simulated here (the simulator knows each vehicle's true fault). In production
  they would come from the workshop system, and the status would feed an automated retraining job.
