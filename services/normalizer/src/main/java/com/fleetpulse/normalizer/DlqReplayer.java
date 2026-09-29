package com.fleetpulse.normalizer;

import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.EventMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When an OEM is onboarded (a mapping version becomes active), re-publishes that OEM's
 * parked events ({@code dlq_reason=UNMAPPED_OEM}) from the DLQ back to
 * {@code telemetry.raw}, now keyed by VIN. Data an OEM sent before onboarding is
 * therefore not lost; it flows through the normal path.
 *
 * <p>Each (OEM, version) uses its own consumer group with committed offsets, so a
 * restart resumes rather than replaying twice. If it ever did replay twice, the dedup
 * window and idempotent sinks absorb the copies.
 */
final class DlqReplayer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayer.class);

    private final String bootstrap;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, "dlq-replayer"));
    private final Map<String, Map<String, Object>> progress = new ConcurrentHashMap<>();

    DlqReplayer(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    void replay(String oem, EventMapper mapper) {
        int version = mapper.spec().version();
        pool.submit(() -> run(oem, version, mapper));
    }

    Map<String, Map<String, Object>> progress() {
        return progress;
    }

    private void run(String oem, int version, EventMapper mapper) {
        String group = "dlq-replay-" + oem + "-v" + version;
        Properties cp = new Properties();
        cp.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cp.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        cp.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cp.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cp.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000);
        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        pp.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        pp.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        pp.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        AtomicLong scanned = new AtomicLong(), replayed = new AtomicLong();
        Map<String, Object> p = new ConcurrentHashMap<>(Map.of("oem", oem, "version", version, "state", "RUNNING"));
        progress.put(oem, p);
        long t0 = System.currentTimeMillis();
        try (var consumer = new KafkaConsumer<>(cp, new ByteArrayDeserializer(), new ByteArrayDeserializer());
             var producer = new KafkaProducer<>(pp, new ByteArraySerializer(), new ByteArraySerializer())) {
            var parts = consumer.partitionsFor(NormalizerTopology.DLQ).stream()
                    .map(i -> new TopicPartition(NormalizerTopology.DLQ, i.partition())).toList();
            // Replay up to where the DLQ was when the mapping went live; later events use the live path.
            Map<TopicPartition, Long> end = consumer.endOffsets(parts);
            consumer.subscribe(java.util.List.of(NormalizerTopology.DLQ));
            int idle = 0;
            while (idle < 5) {
                var records = consumer.poll(Duration.ofSeconds(1));
                if (records.isEmpty()) idle++;
                for (ConsumerRecord<byte[], byte[]> r : records) {
                    scanned.incrementAndGet();
                    if (!oem.equals(NormalizerTopology.header(r.headers(), "oem"))
                            || !"UNMAPPED_OEM".equals(NormalizerTopology.header(r.headers(), "dlq_reason"))) continue;
                    String vin = null;
                    try {
                        vin = mapper.vinOf(Json.MAPPER.readTree(r.value()));
                    } catch (Exception ignored) {
                        // leave unkeyed; the normalizer will send it back to the DLQ with the real reason
                    }
                    var out = new ProducerRecord<>(NormalizerTopology.RAW,
                            vin == null ? null : vin.getBytes(StandardCharsets.UTF_8), r.value());
                    out.headers().add("oem", oem.getBytes(StandardCharsets.UTF_8))
                            .add("replayed_from_dlq", String.valueOf(r.offset()).getBytes(StandardCharsets.UTF_8));
                    producer.send(out);
                    replayed.incrementAndGet();
                }
                producer.flush();
                consumer.commitSync();
                p.put("scanned", scanned.get());
                p.put("replayed", replayed.get());
                boolean done = consumer.assignment().stream().allMatch(tp -> consumer.position(tp) >= end.getOrDefault(tp, 0L));
                if (done && !consumer.assignment().isEmpty()) break;
            }
            p.put("state", "DONE");
            log.info("dlq replay for {} v{}: {} replayed of {} scanned in {} ms", oem, version,
                    replayed.get(), scanned.get(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            p.put("state", "FAILED: " + e.getMessage());
            log.error("dlq replay for {} failed", oem, e);
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
