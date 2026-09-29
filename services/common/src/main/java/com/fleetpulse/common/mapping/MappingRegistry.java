package com.fleetpulse.common.mapping;

import com.fleetpulse.common.event.Json;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Live view of the active OEM mappings, fed by the compacted {@code oem.mappings} topic.
 *
 * <p>Every instance reads the whole topic from the start (no consumer group), so all
 * gateway and normalizer replicas converge on the same mappings, and a new version is
 * picked up within about a second, with no restart and no dropped traffic. A spec that
 * fails to compile is rejected and the previous version stays active.
 */
public final class MappingRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MappingRegistry.class);
    public static final String TOPIC = "oem.mappings";

    private final Map<String, EventMapper> active = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, EventMapper>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread thread;

    /** Current mapper for an OEM, or null if the OEM has not been onboarded. */
    public EventMapper get(String oem) {
        return oem == null ? null : active.get(oem.toUpperCase(Locale.ROOT));
    }

    public Map<String, Integer> versions() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        active.forEach((k, v) -> out.put(k, v.spec().version()));
        return out;
    }

    /** Called with (oem, mapper) whenever a new version becomes active. */
    public void onActivated(BiConsumer<String, EventMapper> listener) {
        listeners.add(listener);
    }

    /**
     * Applies one record from the topic. Returns true if it became active.
     * Older or equal versions are ignored, so replays and reordering are harmless.
     */
    public boolean apply(String oem, String specJson) {
        if (oem == null || specJson == null) return false;
        String key = oem.toUpperCase(Locale.ROOT);
        try {
            MappingSpec spec = Json.MAPPER.readValue(specJson, MappingSpec.class);
            if (!key.equals(spec.oem().toUpperCase(Locale.ROOT))) {
                log.warn("mapping key {} does not match spec OEM {}; ignored", key, spec.oem());
                return false;
            }
            EventMapper current = active.get(key);
            if (current != null && current.spec().version() >= spec.version()) return false;
            EventMapper compiled = EventMapper.compile(spec);
            active.put(key, compiled);
            log.info("mapping activated: {} v{}", key, spec.version());
            for (var l : listeners) l.accept(key, compiled);
            return true;
        } catch (Exception e) {
            log.error("mapping for {} rejected, keeping previous version: {}", key, e.getMessage());
            return false;
        }
    }

    /** Starts following the topic and blocks until the existing records are loaded. */
    public MappingRegistry start(String bootstrap, Duration loadTimeout) throws InterruptedException {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        var loaded = new java.util.concurrent.CountDownLatch(1);
        thread = new Thread(() -> {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
                List<TopicPartition> parts = c.partitionsFor(TOPIC, Duration.ofSeconds(30)).stream()
                        .map(i -> new TopicPartition(TOPIC, i.partition())).toList();
                c.assign(parts);
                c.seekToBeginning(parts);
                Map<TopicPartition, Long> end = c.endOffsets(parts);
                while (running) {
                    for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) apply(r.key(), r.value());
                    if (loaded.getCount() > 0 && parts.stream().allMatch(tp -> c.position(tp) >= end.get(tp))) loaded.countDown();
                }
            } catch (Exception e) {
                log.error("mapping registry stopped", e);
            }
        }, "mapping-registry");
        thread.setDaemon(true);
        thread.start();
        if (!loaded.await(loadTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
            log.warn("mapping registry did not finish loading within {}", loadTimeout);
        return this;
    }

    @Override
    public void close() {
        running = false;
    }
}
