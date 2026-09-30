#!/usr/bin/env bash
# Chaos test: hard-kill the Kafka broker while telemetry is flowing, bring it back, and check
# that every valid, unique event the simulator sent was stored exactly once.
#
#   tests/chaos/broker-kill.sh [seconds-down]      (default 30)
#
# Needs the compose stack running with live traffic. Writes a report to tests/chaos/results/.
# What it proves is exactly what it measures: for this run, after recovery, sent == stored unique
# events == events counted by the per-minute rollup (which would count a re-inserted batch twice).
set -euo pipefail

DOWN=${1:-30}
SIM=${SIM_URL:-http://localhost:8090}
CH=${CH_URL:-http://localhost:8123}
CH_AUTH=${CH_AUTH:-fleet:clickhouse_dev}
KAFKA=${KAFKA_CONTAINER:-fleetpulse-kafka-1}
OUT=tests/chaos/results/broker-kill-$(date -u +%Y%m%dT%H%M%SZ).txt
mkdir -p "$(dirname "$OUT")"

PY=${PYTHON:-python3}   # e.g. PYTHON="uv run --no-project python" where python3 is not on PATH
json() { $PY -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
stats() { curl -fsS "$SIM/admin/stats"; }
sent() { stats | json "sum(d['ledgerValidUnique'].values())"; }
ch() { curl -fsS --user "$CH_AUTH" --data-binary "$1" "$CH/"; }
# Everything the simulator has sent since it started carries an event time from then on.
SINCE="toStartOfMinute(parseDateTimeBestEffort('$(stats | json "d['startedAt']")'))"
stored() { ch "SELECT uniqExact(vin, seq) FROM fleet.telemetry WHERE ts >= $SINCE"; }
# The per-minute rollup has no deduplicating key: a batch inserted twice is counted twice there.
rollup() { ch "SELECT countMerge(samples) FROM fleet.telemetry_1m WHERE minute >= $SINCE"; }
log() { echo "$(date -u +%H:%M:%S) $*" | tee -a "$OUT"; }
# Never leave the simulator paused, however the script ends.
trap 'curl -fsS -X POST "$SIM/admin/pause?paused=false" >/dev/null || true' EXIT

log "broker-kill test: broker down for ${DOWN}s"
log "before: sent=$(sent) stored=$(stored)"

log "docker kill $KAFKA (SIGKILL, no clean shutdown)"
docker kill "$KAFKA" >/dev/null
KILLED=$(date +%s)
sleep "$DOWN"
log "during outage: sent=$(sent) (the simulator keeps generating; the gateway refuses and it retries)"

docker start "$KAFKA" >/dev/null
log "broker restarted; waiting for it to answer requests (healthcheck)"
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$KAFKA")" = healthy ]; do
  [ $(( $(date +%s) - KILLED )) -gt 900 ] && { log "FAIL: broker not healthy 15 minutes after the kill"; exit 1; }
  sleep 2
done
log "broker healthy after $(( $(date +%s) - KILLED ))s from the kill"

sleep 60   # let the pipeline catch up with live traffic flowing
log "pausing new traffic to reconcile"
curl -fsS -X POST "$SIM/admin/pause?paused=true" >/dev/null
for i in $(seq 1 90); do
  s=$(sent); t=$(stored)
  log "settling: sent=$s stored=$t diff=$((s - t))"
  [ "$s" -eq "$t" ] && break
  sleep 10
done

s=$(sent); t=$(stored); r=$(rollup)
if [ "$s" -eq "$t" ] && [ "$r" -eq "$t" ]; then
  log "PASS: sent=$s, stored unique (vin, seq)=$t, counted in the per-minute rollup=$r (no event lost or counted twice)"
else
  log "FAIL: sent=$s, stored unique (vin, seq)=$t, counted in the per-minute rollup=$r"; exit 1
fi
log "report: $OUT"
