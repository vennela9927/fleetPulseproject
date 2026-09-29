package com.fleetpulse.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.EventMapper;
import com.fleetpulse.common.mapping.MappingException;
import com.fleetpulse.common.mapping.MappingSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Dry run of a proposed mapping before anyone approves it: compiles the spec with the same
 * engine production uses, then maps the most recent events that were parked because the OEM
 * had no mapping yet. The result is evidence ("200 of 200 parked events map cleanly"), not a
 * promise. Nothing is written anywhere.
 */
final class MappingPreview {

    static final int SAMPLE = 200;
    /** Records read from the end of each DLQ partition when looking for samples. */
    private static final int TAIL_PER_PARTITION = 5_000;

    private final String bootstrap;
    private final Clock clock;

    MappingPreview(String bootstrap, Clock clock) {
        this.bootstrap = bootstrap;
        this.clock = clock;
    }

    Map<String, Object> run(String specJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        EventMapper mapper;
        try {
            mapper = EventMapper.compile(Json.MAPPER.readValue(specJson, MappingSpec.class));
        } catch (Exception e) {
            out.put("compiled", false);
            out.put("error", String.valueOf(e.getMessage()));
            return out;
        }
        String oem = mapper.spec().oem().toUpperCase();
        out.put("compiled", true);
        out.put("oem", oem);
        out.put("version", mapper.spec().version());

        List<byte[]> samples = parkedSamples(oem);
        int ok = 0;
        Map<String, Map<String, Object>> failures = new LinkedHashMap<>();
        CanonicalEvent example = null;
        for (byte[] raw : samples) {
            try {
                JsonNode payload = Json.MAPPER.readTree(raw);
                CanonicalEvent e = mapper.map(payload, clock.instant());
                ok++;
                if (example == null) example = e;
            } catch (MappingException e) {
                failures.computeIfAbsent(e.reason(), r -> new LinkedHashMap<>(Map.of("reason", r, "count", 0, "example", String.valueOf(e.getMessage()))))
                        .merge("count", 1, (a, b) -> (Integer) a + (Integer) b);
            } catch (Exception e) {
                failures.computeIfAbsent("MALFORMED_JSON", r -> new LinkedHashMap<>(Map.of("reason", r, "count", 0, "example", String.valueOf(e.getMessage()))))
                        .merge("count", 1, (a, b) -> (Integer) a + (Integer) b);
            }
        }
        out.put("sampled", samples.size());
        out.put("mapped", ok);
        out.put("failures", List.copyOf(failures.values()));
        out.put("example", example);
        return out;
    }

    /** The newest (up to {@value #SAMPLE}) parked payloads for {@code oem}. */
    private List<byte[]> parkedSamples(String oem) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000);
        Deque<byte[]> newest = new ArrayDeque<>(SAMPLE);
        try (var consumer = new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> parts = consumer.partitionsFor(NormalizerTopology.DLQ).stream()
                    .map(i -> new TopicPartition(NormalizerTopology.DLQ, i.partition())).toList();
            consumer.assign(parts);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(parts), end = consumer.endOffsets(parts);
            for (TopicPartition tp : parts) consumer.seek(tp, Math.max(begin.get(tp), end.get(tp) - TAIL_PER_PARTITION));
            long deadline = clock.millis() + 10_000;
            while (clock.millis() < deadline
                    && parts.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (!oem.equals(NormalizerTopology.header(r.headers(), "oem"))
                            || !"UNMAPPED_OEM".equals(NormalizerTopology.header(r.headers(), "dlq_reason"))) continue;
                    if (newest.size() == SAMPLE) newest.removeFirst();
                    newest.addLast(r.value());
                }
            }
        }
        return List.copyOf(newest);
    }
}
