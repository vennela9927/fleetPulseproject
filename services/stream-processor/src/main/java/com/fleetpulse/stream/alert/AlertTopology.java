package com.fleetpulse.stream.alert;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * <pre>
 *  telemetry.canonical ──► detect (per-vehicle rule state) ──► alerts
 * </pre>
 * Runs with {@code exactly_once_v2}: the input offset, the rule-state change and any alert
 * commit in one Kafka transaction, so a crash neither loses an episode in progress nor
 * publishes an alert twice. Input is keyed by VIN, so each vehicle's events are processed
 * in order by a single task and the state needs no locking.
 */
public final class AlertTopology {

    public static final String CANONICAL = "telemetry.canonical", ALERTS = "alerts";
    static final String STATE_STORE = "alert-rule-state";

    private AlertTopology() {}

    /** Changelog compacted soon after writes, so rebuilding a thread's state reads about the state's
     *  size, not every update since the start. A full replay stalled threads long enough to be fenced,
     *  which triggered another rebuild: a rebalance storm under load. */
    static final Map<String, String> COMPACT_CHANGELOG = Map.of(
            "segment.bytes", String.valueOf(32 * 1024 * 1024), "segment.ms", "600000",
            "min.cleanable.dirty.ratio", "0.1");

    public static Topology build(RuleEngine engine, MeterRegistry metrics) {
        Topology t = new Topology();
        t.addSource("canonical", Serdes.String().deserializer(), Serdes.ByteArray().deserializer(), CANONICAL);
        t.addProcessor("detect", () -> new Detect(engine, metrics), "canonical");
        t.addStateStore(Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(STATE_STORE), Serdes.String(), Serdes.ByteArray())
                .withLoggingEnabled(COMPACT_CHANGELOG), "detect");
        t.addSink("alerts", ALERTS, Serdes.String().serializer(), Serdes.ByteArray().serializer(), "detect");
        return t;
    }

    static final class Detect implements Processor<String, byte[], String, byte[]> {
        private static final Logger log = LoggerFactory.getLogger(Detect.class);
        private final RuleEngine engine;
        private final MeterRegistry metrics;
        private ProcessorContext<String, byte[]> ctx;
        private KeyValueStore<String, byte[]> store;

        Detect(RuleEngine engine, MeterRegistry metrics) {
            this.engine = engine;
            this.metrics = metrics;
        }

        @Override
        public void init(ProcessorContext<String, byte[]> context) {
            this.ctx = context;
            this.store = context.getStateStore(STATE_STORE);
        }

        @Override
        public void process(Record<String, byte[]> rec) {
            CanonicalEvent e;
            try {
                e = Json.read(rec.value(), CanonicalEvent.class);
            } catch (IllegalArgumentException ex) {
                // Canonical events come from our own normaliser, so this is a bug, not bad input.
                metrics.counter("alert_detector_unreadable_events_total").increment();
                log.warn("skipping unreadable canonical event at {}: {}", rec.timestamp(), ex.getMessage());
                return;
            }
            VehicleRuleState state = VehicleRuleState.fromBytes(store.get(e.vin()));
            for (Alert a : engine.evaluate(state, e)) {
                metrics.counter("alerts_raised_total", "rule", a.rule().name()).increment();
                ctx.forward(new Record<>(a.vin(), Json.write(a), rec.timestamp()));
            }
            store.put(e.vin(), state.toBytes());
        }
    }
}
