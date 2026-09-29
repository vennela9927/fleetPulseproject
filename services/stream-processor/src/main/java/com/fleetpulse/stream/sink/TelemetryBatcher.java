package com.fleetpulse.stream.sink;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Batches canonical events per Kafka partition and writes each batch to ClickHouse exactly
 * once, even across crashes and rebalances.
 *
 * <h2>Why this is needed</h2>
 * The raw table collapses redelivered rows, but its rollup views are fed on insert and
 * cannot: a batch inserted twice is counted twice. ClickHouse can drop a repeated insert if
 * it carries the same {@code insert_deduplication_token}, but only if the retry contains
 * exactly the same rows, and a consumer restarting after a crash normally re-batches
 * differently (a different number of records arrive before the flush timer fires).
 *
 * <h2>Protocol, per partition</h2>
 * <ol>
 *   <li>Commit {@code offset = first} with metadata {@code intent:first-last}. This is a
 *       write-ahead record of the batch boundary, stored in Kafka next to the offset.</li>
 *   <li>Insert the rows with token {@code topicId:partition:first-last}.</li>
 *   <li>Commit {@code offset = last + 1} with no metadata.</li>
 * </ol>
 * A crash after 1 or 2 restarts from {@code first}; the new owner reads the intent and closes
 * its first batch at exactly {@code last}, so the retry has the same token and ClickHouse
 * keeps at most one copy. Including the topic id means a recreated topic, whose offsets
 * restart at 0, cannot collide with tokens still in ClickHouse's deduplication window.
 */
final class TelemetryBatcher {

    interface Writer {
        void insert(byte[] gzippedRows, String dedupToken) throws Exception;
    }

    interface Offsets {
        Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> partitions);

        void commit(Map<TopicPartition, OffsetAndMetadata> offsets);
    }

    private static final Logger log = LoggerFactory.getLogger(TelemetryBatcher.class);
    static final String INTENT = "intent:";

    private final Writer writer;
    private final Offsets offsets;
    private final VehicleDirectory vehicles;
    private final MeterRegistry metrics;
    private final String topicId;
    private final int maxRows;
    private final long maxAgeMs;

    private final Map<TopicPartition, Deque<Batch>> batches = new HashMap<>();
    /** Partitions whose first batch must end at a recorded offset. */
    private final Map<TopicPartition, Long> intentEnd = new HashMap<>();

    TelemetryBatcher(Writer writer, Offsets offsets, VehicleDirectory vehicles, MeterRegistry metrics,
                     String topicId, int maxRows, long maxAgeMs) {
        this.writer = writer;
        this.offsets = offsets;
        this.vehicles = vehicles;
        this.metrics = metrics;
        this.topicId = topicId;
        this.maxRows = maxRows;
        this.maxAgeMs = maxAgeMs;
    }

    static final class Batch {
        final TopicPartition tp;
        final long openedMs;
        final TelemetryRows rows = new TelemetryRows();
        long first = -1, last = -1;
        int records;
        boolean sealed;
        byte[] body;   // set once, so a retry resends identical bytes

        Batch(TopicPartition tp, long openedMs) {
            this.tp = tp;
            this.openedMs = openedMs;
        }

        byte[] body() {
            if (body == null) body = rows.finish();
            return body;
        }
    }

    void onAssigned(Collection<TopicPartition> partitions) {
        if (partitions.isEmpty()) return;
        Map<TopicPartition, OffsetAndMetadata> committed = offsets.committed(new HashSet<>(partitions));
        for (TopicPartition tp : partitions) {
            OffsetAndMetadata om = committed.get(tp);
            long[] range = om == null ? null : parseIntent(om.metadata());
            if (range != null && range[0] == om.offset()) {
                intentEnd.put(tp, range[1]);
                log.info("{}: resuming batch {}-{} recorded before a restart", tp, range[0], range[1]);
            }
        }
    }

    void onRevoked(Collection<TopicPartition> partitions) {
        // Unwritten batches are dropped; the next owner re-reads them from the committed offset.
        for (TopicPartition tp : partitions) {
            batches.remove(tp);
            intentEnd.remove(tp);
        }
    }

    void accept(TopicPartition tp, long offset, byte[] value, long nowMs) {
        Deque<Batch> q = batches.computeIfAbsent(tp, k -> new ArrayDeque<>());
        Long end = intentEnd.get(tp);
        if (end != null && offset > end) {
            // The record that closed the recorded batch was not seen (it cannot have been
            // filtered, but be safe): close at the boundary anyway.
            if (!q.isEmpty() && !q.peekLast().sealed) q.peekLast().sealed = true;
            intentEnd.remove(tp);
            end = null;
        }
        Batch b = q.peekLast();
        if (b == null || b.sealed) {
            b = new Batch(tp, nowMs);
            q.addLast(b);
        }
        if (b.first < 0) b.first = offset;
        b.last = offset;
        b.records++;
        addRow(b, value);

        if (end != null && offset == end) {
            b.sealed = true;
            intentEnd.remove(tp);
        } else if (end == null && b.records >= maxRows) {
            b.sealed = true;
        }
    }

    private void addRow(Batch b, byte[] value) {
        CanonicalEvent e;
        try {
            e = Json.read(value, CanonicalEvent.class);
        } catch (IllegalArgumentException ex) {
            metrics.counter("clickhouse_sink_skipped_total", "reason", "unreadable").increment();
            return;
        }
        VehicleDirectory.Vehicle v = vehicles.get(e.vin());
        if (v == null) {
            // tenant_id is part of every row's identity for access control, so a row without
            // an owner cannot be stored. The count is exported so it cannot hide.
            metrics.counter("clickhouse_sink_skipped_total", "reason", "unregistered_vin").increment();
            return;
        }
        b.rows.add(e, v);
    }

    /**
     * Writes every sealed batch, and every open batch older than the flush interval, then
     * commits. On a failed insert, batches already written stay committed and the failed one
     * stays queued with its boundary recorded, so the next call retries it with the same token.
     */
    void flushDue(long nowMs) throws Exception {
        while (true) {
            List<Batch> round = new ArrayList<>();
            for (Deque<Batch> q : batches.values()) {
                Batch b = q.peekFirst();
                if (b == null) continue;
                boolean waitingForIntentEnd = !b.sealed && intentEnd.containsKey(b.tp);
                if (b.sealed || (!waitingForIntentEnd && nowMs - b.openedMs >= maxAgeMs)) {
                    b.sealed = true;
                    round.add(b);
                }
            }
            if (round.isEmpty()) return;
            writeRound(round);
        }
    }

    private void writeRound(List<Batch> round) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> intents = new HashMap<>();
        for (Batch b : round) intents.put(b.tp, new OffsetAndMetadata(b.first, INTENT + b.first + "-" + b.last));
        offsets.commit(intents);

        Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
        try {
            for (Batch b : round) {
                if (b.rows.count() > 0) {
                    writer.insert(b.body(), token(b));
                    metrics.counter("clickhouse_sink_rows_total").increment(b.rows.count());
                }
                done.put(b.tp, new OffsetAndMetadata(b.last + 1, ""));
                batches.get(b.tp).pollFirst();
            }
        } finally {
            if (!done.isEmpty()) offsets.commit(done);
        }
    }

    String token(Batch b) {
        return topicId + ":" + b.tp.partition() + ":" + b.first + "-" + b.last;
    }

    int pendingBatches() {
        return batches.values().stream().mapToInt(Deque::size).sum();
    }

    static long[] parseIntent(String metadata) {
        if (metadata == null || !metadata.startsWith(INTENT)) return null;
        String[] parts = metadata.substring(INTENT.length()).split("-");
        if (parts.length != 2) return null;
        try {
            return new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
