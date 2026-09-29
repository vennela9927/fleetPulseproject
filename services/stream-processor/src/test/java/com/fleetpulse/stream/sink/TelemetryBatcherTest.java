package com.fleetpulse.stream.sink;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.EventType;
import com.fleetpulse.common.event.Json;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exactly-once protocol against ClickHouse, with Kafka's committed offsets and the
 * ClickHouse insert replaced by in-memory fakes so that crashes can be placed precisely.
 */
class TelemetryBatcherTest {

    static final TopicPartition P0 = new TopicPartition("telemetry.canonical", 0);
    static final TopicPartition P1 = new TopicPartition("telemetry.canonical", 1);
    static final String KNOWN = "1HGCM82633A004352", UNKNOWN = "5YJ3E1EA7KF317000";

    final VehicleDirectory vehicles = VehicleDirectory.fixed(
            new VehicleDirectory.Vehicle(7, KNOWN, "00000000-0000-0000-0000-00000000000a", 3, "ICE"));
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final FakeOffsets offsets = new FakeOffsets();
    final FakeClickHouse clickhouse = new FakeClickHouse();

    TelemetryBatcher batcher(int maxRows) {
        return new TelemetryBatcher(clickhouse, offsets, vehicles, metrics, "topicA", maxRows, 1_000);
    }

    static byte[] event(String vin, long seq) {
        return Json.write(new CanonicalEvent(1, vin, "AURORA", seq, Instant.parse("2026-09-29T10:00:00Z").plusSeconds(seq),
                Instant.parse("2026-09-29T10:00:01Z").plusSeconds(seq), 12.9, 77.6, 40, 1000, true, 1500,
                55.0, null, null, 91.0, 14.1, List.of("P0301"), EventType.PERIODIC));
    }

    static void feed(TelemetryBatcher b, TopicPartition tp, long fromOffset, long toOffset) {
        for (long o = fromOffset; o <= toOffset; o++) b.accept(tp, o, event(KNOWN, o), 0);
    }

    @Test
    void aBatchIsRecordedThenInsertedThenCommitted() throws Exception {
        TelemetryBatcher b = batcher(100);
        b.onAssigned(List.of(P0));
        feed(b, P0, 0, 2);
        b.flushDue(999);
        assertThat(clickhouse.tokens).isEmpty();   // not old enough yet

        b.flushDue(1_000);
        assertThat(clickhouse.tokens).containsExactly("topicA:0:0-2");
        assertThat(clickhouse.rows.get(0)).hasSize(3);
        assertThat(offsets.history).containsExactly(
                P0 + "@0 intent:0-2",
                P0 + "@3 ");
        assertThat(metrics.counter("clickhouse_sink_rows_total").count()).isEqualTo(3);
    }

    @Test
    void rowsCarryTheOwnerFromTheVehicleDirectory() throws Exception {
        TelemetryBatcher b = batcher(1);
        b.accept(P0, 0, event(KNOWN, 1), 0);
        b.flushDue(0);
        String row = clickhouse.rows.get(0).get(0);
        assertThat(row).contains("\"tenant_id\":\"00000000-0000-0000-0000-00000000000a\"",
                "\"fleet_id\":3", "\"powertrain\":\"ICE\"", "\"ts\":\"2026-09-29 10:00:01.000\"", "\"dtc\":[\"P0301\"]");
    }

    @Test
    void fullBatchesAreSealedBySizeAndPartitionsAreIndependent() throws Exception {
        TelemetryBatcher b = batcher(2);
        feed(b, P0, 0, 4);
        feed(b, P1, 10, 11);
        b.flushDue(0);   // sealed ones only: P0 [0-1], [2-3] and P1 [10-11]; P0 [4] is still open
        assertThat(clickhouse.tokens).containsExactlyInAnyOrder("topicA:0:0-1", "topicA:0:2-3", "topicA:1:10-11");
        assertThat(offsets.committed.get(P0).offset()).isEqualTo(4);
        assertThat(offsets.committed.get(P1).offset()).isEqualTo(12);
        assertThat(b.pendingBatches()).isEqualTo(1);
    }

    @Test
    void aCrashAfterTheInsertReplaysTheSameBatchBoundaryAndToken() throws Exception {
        TelemetryBatcher first = batcher(100);
        first.onAssigned(List.of(P0));
        feed(first, P0, 0, 2);
        offsets.failOnCommitNumber = 2;   // the commit after the insert never happens
        assertThatThrownBy(() -> first.flushDue(1_000)).hasMessageContaining("crash");
        assertThat(clickhouse.tokens).containsExactly("topicA:0:0-2");
        assertThat(offsets.committed.get(P0)).isEqualTo(new OffsetAndMetadata(0, "intent:0-2"));

        // A new owner resumes from offset 0. More events have arrived since, and its flush
        // timer is different, but the first batch must still end exactly at offset 2.
        offsets.failOnCommitNumber = -1;
        TelemetryBatcher second = batcher(100);
        second.onAssigned(List.of(P0));
        feed(second, P0, 0, 6);
        second.flushDue(1_000);

        assertThat(clickhouse.tokens).containsExactly("topicA:0:0-2", "topicA:0:0-2", "topicA:0:3-6");
        assertThat(clickhouse.distinctRows()).isEqualTo(7);   // ClickHouse keeps one copy per token
        assertThat(offsets.committed.get(P0)).isEqualTo(new OffsetAndMetadata(7, ""));
    }

    @Test
    void aRecordedBatchIsNotFlushedEarlyByTheTimer() throws Exception {
        offsets.committed.put(P0, new OffsetAndMetadata(0, "intent:0-4"));
        TelemetryBatcher b = batcher(100);
        b.onAssigned(List.of(P0));
        feed(b, P0, 0, 2);   // the rest of the recorded batch has not been polled yet
        b.flushDue(60_000);
        assertThat(clickhouse.tokens).isEmpty();
        feed(b, P0, 3, 4);
        b.flushDue(60_000);
        assertThat(clickhouse.tokens).containsExactly("topicA:0:0-4");
    }

    @Test
    void anIntentThatDoesNotMatchTheCommittedOffsetIsIgnored() throws Exception {
        offsets.committed.put(P0, new OffsetAndMetadata(5, "intent:0-4"));   // stale metadata
        TelemetryBatcher b = batcher(100);
        b.onAssigned(List.of(P0));
        feed(b, P0, 5, 6);
        b.flushDue(1_000);
        assertThat(clickhouse.tokens).containsExactly("topicA:0:5-6");
    }

    @Test
    void aFailedInsertIsRetriedWithTheSameTokenAndBytes() throws Exception {
        TelemetryBatcher b = batcher(100);
        feed(b, P0, 0, 2);
        clickhouse.failNext = 1;
        assertThatThrownBy(() -> b.flushDue(1_000)).hasMessageContaining("ClickHouse down");
        assertThat(offsets.committed.get(P0)).isEqualTo(new OffsetAndMetadata(0, "intent:0-2"));

        b.flushDue(2_000);
        assertThat(clickhouse.tokens).containsExactly("topicA:0:0-2");
        assertThat(offsets.committed.get(P0).offset()).isEqualTo(3);
        assertThat(b.pendingBatches()).isZero();
    }

    @Test
    void revokedPartitionsDropTheirUnwrittenBatches() throws Exception {
        TelemetryBatcher b = batcher(100);
        feed(b, P0, 0, 2);
        feed(b, P1, 0, 2);
        b.onRevoked(List.of(P0));
        b.flushDue(1_000);
        assertThat(clickhouse.tokens).containsExactly("topicA:1:0-2");
    }

    @Test
    void unregisteredVehiclesAreCountedNotStoredAndAnEmptyBatchStillCommits() throws Exception {
        TelemetryBatcher b = batcher(100);
        b.accept(P0, 0, event(UNKNOWN, 1), 0);
        b.accept(P0, 1, "{broken".getBytes(), 0);
        b.flushDue(1_000);
        assertThat(clickhouse.tokens).isEmpty();
        assertThat(offsets.committed.get(P0).offset()).isEqualTo(2);
        assertThat(metrics.counter("clickhouse_sink_skipped_total", "reason", "unregistered_vin").count()).isEqualTo(1);
        assertThat(metrics.counter("clickhouse_sink_skipped_total", "reason", "unreadable").count()).isEqualTo(1);
    }

    @Test
    void intentMetadataParsing() {
        assertThat(TelemetryBatcher.parseIntent("intent:10-20")).containsExactly(10, 20);
        assertThat(TelemetryBatcher.parseIntent("")).isNull();
        assertThat(TelemetryBatcher.parseIntent(null)).isNull();
        assertThat(TelemetryBatcher.parseIntent("intent:x-1")).isNull();
        assertThat(TelemetryBatcher.parseIntent("intent:5")).isNull();
    }

    /** Kafka's committed offsets for the group, with an optional crash on the n-th commit. */
    static final class FakeOffsets implements TelemetryBatcher.Offsets {
        final Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        final List<String> history = new ArrayList<>();
        int commits;
        int failOnCommitNumber = -1;

        @Override
        public Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> tps) {
            Map<TopicPartition, OffsetAndMetadata> out = new HashMap<>();
            for (TopicPartition tp : tps) if (committed.containsKey(tp)) out.put(tp, committed.get(tp));
            return out;
        }

        @Override
        public void commit(Map<TopicPartition, OffsetAndMetadata> o) {
            if (++commits == failOnCommitNumber) throw new IllegalStateException("simulated crash");
            committed.putAll(o);
            o.forEach((tp, om) -> history.add(tp + "@" + om.offset() + " " + om.metadata()));
        }
    }

    /** Records inserts; like ClickHouse with a deduplication window, keeps one copy per token. */
    static final class FakeClickHouse implements TelemetryBatcher.Writer {
        final List<String> tokens = new ArrayList<>();
        final List<List<String>> rows = new ArrayList<>();
        final Map<String, List<String>> stored = new HashMap<>();
        int failNext;

        @Override
        public void insert(byte[] gzippedRows, String token) throws IOException {
            if (failNext > 0) {
                failNext--;
                throw new IOException("ClickHouse down");
            }
            List<String> lines;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzippedRows))) {
                lines = new String(in.readAllBytes()).lines().toList();
            }
            tokens.add(token);
            rows.add(lines);
            stored.putIfAbsent(token, lines);
        }

        int distinctRows() {
            return stored.values().stream().mapToInt(List::size).sum();
        }
    }
}
