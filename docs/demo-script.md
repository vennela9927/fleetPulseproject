# Demo script (5 minutes)

Follows the demo plan in the solution document (section 13). **Do** is what to click; **Say** is what
to say. Every number is from a test or measurement in this repository, on simulated data.

## Before recording

| When | Do |
|---|---|
| 60 min before | Start Docker Desktop and wait for "Engine running". In the repo: `docker compose up -d --wait`. Feed drift judges a maker only after an hour of continuous data. |
| 55 min before | Make Draco an unknown maker again: `bash scripts/demo/reset-draco.sh`, then `docker compose restart ingest-gateway normalizer simulator`. |
| 50 min before | After any unclean shutdown (laptop sleep, restart, force-quit), clear Kafka transactions left open, which silently block data. In Git Bash: `export MSYS_NO_PATHCONV=1`, then `docker exec fleetpulse-kafka-1 /opt/kafka/bin/kafka-transactions.sh --bootstrap-server localhost:9092 find-hanging --broker-id 1`. For each row: `... abort --topic <Topic> --partition <Partition> --start-offset <StartOffset>`. |
| 15 min before | Open http://localhost:3000. Overview shows 100% reporting; Chaos shows sent close to stored. |
| 8 min before | Second browser (private window) as `ops.admin` / `ops-demo-2026` → Vehicle makers → **Demo: Aurora firmware bug**. It is flagged within about 5 minutes and named as "mph sent as km/h" at about 7. |
| 2 min before | **Tab 1** `acme.manager` / `acme-demo-2026` on Overview. **Tab 2** ops.admin on Vehicle makers. **Tab 3** manager on Chaos. |

## 0:00 - 0:30 · Problem

**Do:** Tab 1, Overview. Scroll from the tiles to Fleet health.

**Say:**
> "Fleets buy vehicles from several makers, and each maker sends data in its own format. So fleets
> still lose vehicles to breakdowns whose warning signs were already in the data. FleetPulse predicts
> which vehicles will break down in the next 7 days, for a mixed-maker fleet, and turns that into a
> workshop plan a manager approves.
> We simulate 100,000 vehicles from four makers, shared by two companies; this manager sees their
> 50,000. Fleet health: 350 to inspect tomorrow, about 580 breakdowns expected this week, $314,000
> saved if the plan is followed, and tomorrow's workshop bays are already full."

## 0:30 - 2:00 · Solution and live demo 1

**Step 1, the first vehicle in line (0:30 - 0:55)**
**Do:** Fleet health → First in line → click the first VIN.
**Say:**
> "The first vehicle in line: 99% likely to break down this week, most likely the 12-volt battery. The
> reasons are in its data: the battery voltage has been falling for a week, here. It is a cheap fix,
> about $58 net, so when the bays ran out, the plan gave them to bigger repairs, like high-voltage
> batteries worth about $2,300 each. The page flags it as too risky to wait."

**Step 2, the back-test (0:55 - 1:20)**
**Do:** Sidebar → At risk. Point at "How well it predicts", then "The model, graded by the workshop".
**Say:**
> "Is it any good? On vehicles and days the model never saw, at the same number of inspections as a
> mechanic's rule, it caught 80% of breakdowns against 54%. With a $150 inspection that is about
> $712,000 a week per 100,000 vehicles, on our simulated data. After launch, mechanics grade it:
> here it caught its own overconfidence, 86% faults found against 96% promised."

**Step 3, copilot, approval, audit trail (1:20 - 2:00)**
**Do:** Sidebar → Copilot. Type `Book vehicle <id> for service` (the id from the vehicle's URL) → Enter →
wait for the proposal card → **Approve** → **Audit trail**.
**Say:**
> "The manager decides this one cannot wait and asks the copilot to book it. The copilot can only
> propose: nothing is booked until a manager approves. Approved. The audit trail shows the copilot
> proposing and the manager approving, in a hash-chained log, so a later edit would show."

## 2:00 - 3:30 · Live demo 2

**Step 1, parked (2:00 - 2:20)**
**Do:** Tab 2, Vehicle makers. Point at Draco's parked events.
**Say:**
> "A new maker, Draco, 2,000 trucks, starts sending a format we have never seen. Its data is not
> dropped: it is parked until we know how to read it."

**Step 2, preview and approve (2:20 - 2:55)**
**Do:** Choose File → `infra/onboarding/draco-mapping.json` → **Preview** → point at the result →
**Approve**.
**Say:**
> "A new maker is a mapping file, not code. Before approving, we preview it on Draco's real parked data
> with the production engine: every record maps cleanly. Approve."

**Step 3, live without a restart (2:55 - 3:10)**
**Do:** Tab 1, Overview map: Draco trucks appear.
**Say:**
> "Every service picked it up within about a second, with no restart, and the parked data was
> replayed, so nothing Draco sent was lost."

**Step 4, feed drift (3:10 - 3:30)**
**Do:** Tab 2 → Feed health: Aurora speed DRIFT and its hint. Then **Fix it**.
**Say:**
> "Data can be valid and still wrong. An Aurora firmware update started sending speed in mph labelled
> as km/h; no validation rule catches that. Feed drift flagged Aurora alone and named the likely
> cause. In our test it flagged it within 5 minutes and stayed quiet with no bug."

## 3:30 - 4:45 · Under the hood (explained, not run live)

**Do:** Tab 3, Chaos. Leave it on screen; point at each part as you talk about it.

**Say, the counts (3:30 - 3:55):**
> "This is how we check the pipeline is trustworthy. The Chaos page counts every event the vehicles
> sent against what was stored, per maker. While traffic flows, the difference is just events still in
> the pipeline: a few seconds' worth."

**Say, what was sent on purpose (3:55 - 4:10):**
> "The simulator also sends bad data on purpose, like a real fleet: duplicates, corrupt records and
> events that arrive out of order. This line shows they were caught: none stored twice, none stored
> corrupt."

**Say, the failure test (4:10 - 4:45):**
> "We use this page to break things deliberately. With 100,000 vehicles reporting, we hard-killed
> the Kafka broker, the message backbone, for 30 seconds. The gateway refused new data and the
> vehicles retried, so nothing was acknowledged that wasn't stored. After recovery, pausing traffic
> let the numbers meet: 2,874,026 sent, 2,874,026 stored, and the same count in the per-minute
> rollup, so nothing lost and nothing counted twice. The buttons here inject faults, break a sensor or
> spike the traffic for these tests; the full run is in the repository's chaos test results."

## 4:45 - 5:00 · Impact and next steps

**Do:** End on the Overview or the README.
**Say:**
> "It runs with one command, and the same images deploy to AWS or Google Cloud with Helm, changing
> configuration only. On one laptop it keeps up with 100,000 vehicles reporting every 30 seconds.
> Next: real fleet data, a three-broker cluster and a cloud deployment. Thank you."

## If something goes wrong

| Problem | Fix |
|---|---|
| Overview shows "Loading…" or 0 reporting | The stack needs about 10 minutes after starting; reload. If stored stays at 0 on the Chaos page, check for open Kafka transactions (see "Before recording"). |
| Copilot is slow or Gemini fails | The rule-based fallback answers; propose and approve work the same. |
| Feed health shows no DRIFT yet | The bug button was pressed too late: say "it is flagged within about 5 minutes". |

## Recording tips

- Record each segment separately and join them, so a mistake costs one segment.
- Say "in our tests" or "on the simulated data"; never "guaranteed", "never loses data", or "catches
  80% of breakdowns" without the conditions.
