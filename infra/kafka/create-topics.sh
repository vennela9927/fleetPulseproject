#!/bin/bash
# Creates all topics idempotently. Replication factor comes from the environment:
# 1 for the single-broker dev stack, 3 for the chaos profile and production.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
RF="${TOPIC_REPLICATION:-1}"
MIN_ISR=$(( RF > 1 ? 2 : 1 ))
KT=/opt/kafka/bin/kafka-topics.sh

until $KT --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; do
  echo "waiting for kafka at $BOOTSTRAP ..."; sleep 2
done

create() {  # name partitions extra-configs...
  local name=$1 parts=$2; shift 2
  local cfg=(--config min.insync.replicas=$MIN_ISR)
  for c in "$@"; do cfg+=(--config "$c"); done
  $KT --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$name" \
      --partitions "$parts" --replication-factor "$RF" "${cfg[@]}"
}

# Raw OEM payloads, keyed by VIN so each vehicle's events stay in order on one partition.
# Kept 7 days: the replay window after a mapping fix or a consumer bug.
create telemetry.raw        24 retention.ms=604800000  compression.type=producer
# Normalised canonical events.
create telemetry.canonical  24 retention.ms=259200000  compression.type=producer
# Events that failed validation or had no active mapping, with the reason in headers.
create telemetry.dlq         6 retention.ms=1209600000
# Active OEM mapping specs, one record per OEM. Compacted: the latest version wins,
# and every normalizer instance rebuilds the full set on startup.
create oem.mappings          1 cleanup.policy=compact
# Alerts raised by the stream processor.
create alerts                6 retention.ms=604800000

$KT --bootstrap-server "$BOOTSTRAP" --list
