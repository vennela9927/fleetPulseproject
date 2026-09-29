#!/usr/bin/env bash
# Puts Draco back to "not onboarded" so the onboarding demo can be run again.
# Run from the repo root with the compose stack up. Afterwards restart the gateway,
# normalizer and simulator (their mapping registries and ledgers are in memory).
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep container paths as written

KT="docker compose exec -T kafka /opt/kafka/bin"
echo "1/5 recreating oem.mappings with the built-in mappings only"
$KT/kafka-topics.sh --bootstrap-server kafka:9092 --delete --topic oem.mappings 2>/dev/null || true
until ! $KT/kafka-topics.sh --bootstrap-server kafka:9092 --list | grep -qx oem.mappings; do sleep 1; done
$KT/kafka-topics.sh --bootstrap-server kafka:9092 --create --topic oem.mappings --partitions 1 \
  --replication-factor 1 --config cleanup.policy=compact
for f in services/common/src/main/resources/mappings/*.json; do
  oem=$(python -c "import json,sys; print(json.load(open(sys.argv[1]))['oem'])" "$f")
  printf '%s|%s\n' "$oem" "$(python -c "import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))" "$f")" \
    | $KT/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic oem.mappings \
        --property parse.key=true --property key.separator='|'
done

echo "2/5 forgetting the previous replay"
for g in $($KT/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --list | grep '^dlq-replay-DRACO' || true); do
  $KT/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --delete --group "$g"
done

echo "3/5 removing Draco from Postgres"
docker compose exec -T postgres psql -U fleet_admin -d fleet -q -c "DELETE FROM oem_mapping WHERE oem_code = 'DRACO'"

echo "4/5 removing Draco telemetry from ClickHouse"
for t in telemetry telemetry_1m vehicle_daily; do
  docker compose exec -T clickhouse clickhouse-client -u fleet --password "${CLICKHOUSE_PASSWORD:-clickhouse_dev}" \
    -q "ALTER TABLE fleet.$t DELETE WHERE startsWith(vin, 'DRC') SETTINGS mutations_sync = 1"
done

echo "5/5 removing Draco live state from Redis"
docker compose exec -T redis sh -c '
  for k in $(redis-cli --scan --pattern "map:*"); do
    redis-cli HKEYS "$k" | grep "^DRC" | xargs -r redis-cli HDEL "$k" >/dev/null
  done
  for k in $(redis-cli --scan --pattern "geo:*"); do
    redis-cli ZRANGE "$k" 0 -1 | grep "^DRC" | xargs -r redis-cli ZREM "$k" >/dev/null
  done
  redis-cli --scan --pattern "v:DRC*" | xargs -r redis-cli DEL >/dev/null'
echo "done: restart the gateway, normalizer and simulator"
