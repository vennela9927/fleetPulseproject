package com.fleetpulse.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.EventMapper;
import com.fleetpulse.common.mapping.MappingException;
import com.fleetpulse.common.mapping.MappingRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * <pre>
 *  telemetry.raw ──► normalize ──► dedup ──► telemetry.canonical
 *                        │
 *                        └──────────────────► telemetry.dlq  (reason in headers)
 * </pre>
 * Runs with {@code processing.guarantee=exactly_once_v2}: the read offset, the dedup state
 * change and the output records commit in one Kafka transaction. A crash in the middle
 * replays the input but never emits twice or loses the dedup state.
 */
public final class NormalizerTopology {

    static final String RAW = "telemetry.raw", CANONICAL = "telemetry.canonical", DLQ = "telemetry.dlq";
    static final String DEDUP_STORE = "dedup-seq-window";

    private NormalizerTopology() {}

    /** Changelog compacted soon after writes, so rebuilding a thread's state reads about the state's
     *  size, not every update since the start. A full replay stalled threads long enough to be fenced,
     *  which triggered another rebuild: a rebalance storm under load. */
    static final Map<String, String> COMPACT_CHANGELOG = Map.of(
            "segment.bytes", String.valueOf(32 * 1024 * 1024), "segment.ms", "600000",
            "min.cleanable.dirty.ratio", "0.1");

    public static Topology build(MappingRegistry registry, MeterRegistry metrics, Clock clock) {
        Topology t = new Topology();
        t.addSource("raw", Serdes.ByteArray().deserializer(), Serdes.ByteArray().deserializer(), RAW);
        t.addProcessor("normalize", () -> new Normalize(registry, metrics, clock), "raw");
        t.addProcessor("dedup", () -> new Dedup(metrics), "normalize");
        t.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(DEDUP_STORE), Serdes.String(), Serdes.ByteArray())
                .withLoggingEnabled(COMPACT_CHANGELOG), "dedup");
        t.addSink("canonical", CANONICAL, Serdes.String().serializer(), Serdes.ByteArray().serializer(), "dedup");
        t.addSink("dlq", DLQ, Serdes.ByteArray().serializer(), Serdes.ByteArray().serializer(), "normalize");
        return t;
    }

    /** Maps raw → canonical using the OEM's active mapping; failures go to the DLQ child. */
    static final class Normalize implements Processor<byte[], byte[], Object, Object> {
        private final MappingRegistry registry;
        private final MeterRegistry metrics;
        private final Clock clock;
        private ProcessorContext<Object, Object> ctx;

        Normalize(MappingRegistry registry, MeterRegistry metrics, Clock clock) {
            this.registry = registry;
            this.metrics = metrics;
            this.clock = clock;
        }

        @Override
        public void init(ProcessorContext<Object, Object> context) {
            this.ctx = context;
        }

        @Override
        public void process(Record<byte[], byte[]> rec) {
            String oem = header(rec.headers(), "oem");
            EventMapper mapper = registry.get(oem);
            if (mapper == null) {
                dlq(rec, oem, "UNMAPPED_OEM", "no active mapping for " + oem);
                return;
            }
            try {
                JsonNode payload = Json.MAPPER.readTree(rec.value());
                Instant now = clock.instant();
                CanonicalEvent e = mapper.map(payload, now).withIngestTs(now);
                metrics.counter("normalizer_events_total", "oem", oem).increment();
                ctx.forward(new Record<Object, Object>(e.vin(), e, rec.timestamp()), "dedup");
            } catch (MappingException e) {
                dlq(rec, oem, e.reason(), e.getMessage());
            } catch (Exception e) {
                dlq(rec, oem, "MALFORMED_JSON", String.valueOf(e.getMessage()));
            }
        }

        private void dlq(Record<byte[], byte[]> rec, String oem, String reason, String detail) {
            metrics.counter("normalizer_dlq_total", "oem", String.valueOf(oem), "reason", reason).increment();
            Record<Object, Object> out = new Record<>(rec.key(), rec.value(), rec.timestamp(), rec.headers());
            out.headers().remove("dlq_reason").remove("dlq_detail")
                    .add("dlq_reason", reason.getBytes(StandardCharsets.UTF_8))
                    .add("dlq_detail", truncate(detail).getBytes(StandardCharsets.UTF_8));
            ctx.forward(out, "dlq");
        }
    }

    /** Drops events whose (vin, seq) was seen recently. */
    static final class Dedup implements Processor<Object, Object, String, byte[]> {
        private final MeterRegistry metrics;
        private ProcessorContext<String, byte[]> ctx;
        private KeyValueStore<String, byte[]> store;

        Dedup(MeterRegistry metrics) {
            this.metrics = metrics;
        }

        @Override
        public void init(ProcessorContext<String, byte[]> context) {
            this.ctx = context;
            this.store = context.getStateStore(DEDUP_STORE);
        }

        @Override
        public void process(Record<Object, Object> rec) {
            CanonicalEvent e = (CanonicalEvent) rec.value();
            SeqWindow window = SeqWindow.fromBytes(store.get(e.vin()));
            if (!window.add(e.seq())) {
                metrics.counter("normalizer_duplicates_dropped_total", "oem", e.oem()).increment();
                return;
            }
            store.put(e.vin(), window.toBytes());
            ctx.forward(new Record<>(e.vin(), Json.write(e), e.ts().toEpochMilli()));
        }
    }

    static String header(Headers headers, String name) {
        Header h = headers.lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
