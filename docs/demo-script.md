# Demo script (under 5 minutes)

## Before recording

1. `docker compose up -d --wait` at least **one hour** before recording. Feed drift judges a maker only
   after an hour of continuous data, so the firmware-bug moment needs it.
2. `bash scripts/demo/reset-draco.sh`, then `docker compose restart ingest-gateway normalizer simulator`,
   so Draco is an unknown maker again. Check that the Vehicle makers page shows Draco's events parked.
3. Optional: `GEMINI_API_KEY` in `.env` for the language-model copilot. Without it, the rule-based
   assistant answers and the approval flow is identical.
4. Browser windows signed in as `acme.manager` / `acme-demo-2026` and `ops.admin` / `ops-demo-2026`.
   A terminal in the repository root.

## 0:00 to 0:30: the problem

"Fleets buy from several makers, each with its own data format. Breakdowns still happen even though
the warning signs are in the data. FleetPulse predicts which vehicles will break down in the next
7 days, for a mixed-maker fleet, and turns that into a workshop plan a manager approves.
100,000 simulated vehicles, four makers."

Show **Overview**: live tiles and map, then the **Fleet health** section: vehicles to inspect
tomorrow, worth inspecting, tomorrow's bays, today's actions.

## 0:30 to 2:00: a breakdown avoided (demo 3)

1. Click the first vehicle in **First in line**: risk, likely part, the reasons, money at stake.
2. **Breakdown risk** page: "same number of checks as a mechanic's rule: 80% of breakdowns caught
   against 54%, on held-out vehicles and later days of simulated data". Point at the workshop
   grading: "the model is graded by what mechanics find; it caught our 99% cap as overconfident".
3. **Copilot**: "Book vehicle <id> for service" → a proposal appears, nothing is booked →
   **Approve** → **Audit trail**: the copilot proposed, the manager approved, hash-chained.

## 2:00 to 3:30: a new maker, live (demo 2)

As `ops.admin`, **Vehicle makers**:
1. Draco's events are parked, not lost.
2. Propose the mapping (`infra/onboarding/draco-mapping.json`) → **Preview** on the parked events
   ("N of N map cleanly") → **Approve**.
3. The parked events replay; Draco appears on the map without any restart.
4. **Demo: Aurora firmware bug**. Press it about 7 minutes before you show Feed health (for
   example at the start of the recording): it flags Aurora speed within 5 minutes and names the
   cause, "looks like mph sent as km/h", at about 7. Then **Fix it**. Aurora stays on WATCH for up
   to 40 minutes afterwards, while the bug period is still in its baseline.

## 3:30 to 4:45: nothing lost (demo 1)

1. **Chaos** page open, sent and stored counting up.
2. Terminal: `docker kill fleetpulse-kafka-1`, wait about 30 s, then `docker start fleetpulse-kafka-1`.
3. While it recovers: "the gateway refuses and the simulator retries; nothing is acknowledged that
   isn't in Kafka".
4. Pause traffic on the Chaos page → sent and stored meet: "in our scripted run, 2,874,026 sent,
   2,874,026 stored, and the same count in the per-minute rollup, which would double-count a
   re-inserted batch". Duplicates, invalid and out-of-order events were also sent on purpose and
   not stored.
5. Resume traffic.

## 4:45 to 5:00: close

"One `docker compose up`; the same images deploy to AWS or GCP with Helm by changing values only.
Everything shown is measured on simulated data; the solution document says what was tested and how."

## Words to avoid

Say "in our tests" or "on the simulated data", not "guaranteed", "never loses data" or "catches 80%
of breakdowns" without the conditions.
