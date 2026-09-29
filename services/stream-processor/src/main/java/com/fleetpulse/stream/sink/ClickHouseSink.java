package com.fleetpulse.stream.sink;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Consumer loop around {@link TelemetryBatcher}. Runs in its own consumer group, so a slow
 * or unavailable ClickHouse delays stored telemetry but never live alerts.
 *
 * <p>When an insert fails the loop pauses its partitions and keeps polling (so it stays in
 * the group), retrying with backoff. Nothing is dropped: the data waits in Kafka.
 */
public final class ClickHouseSink implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseSink.class);
    static final String TOPIC = "telemetry.canonical";

    private final KafkaConsumer<String, byte[]> consumer;
    private final TelemetryBatcher batcher;
    private final MeterRegistry metrics;
    private volatile boolean running = true;
    private volatile String lastError;

    public ClickHouseSink(String bootstrap, String clickhouseUrl, String user, String password,
                          VehicleDirectory vehicles, MeterRegistry metrics, int maxRows, long flushMs) throws Exception {
        this.metrics = metrics;
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "clickhouse-sink");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // The normaliser writes transactionally; never store an aborted event.
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumer = new KafkaConsumer<>(p);

        TelemetryBatcher.Offsets offsets = new TelemetryBatcher.Offsets() {
            @Override
            public Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> tps) {
                return consumer.committed(tps);
            }

            @Override
            public void commit(Map<TopicPartition, OffsetAndMetadata> o) {
                consumer.commitSync(o);
            }
        };
        batcher = new TelemetryBatcher(new HttpClickHouseWriter(clickhouseUrl, user, password), offsets,
                vehicles, metrics, topicId(bootstrap), maxRows, flushMs);
    }

    /** Kafka's id for the topic, which changes if the topic is deleted and recreated. */
    private static String topicId(String bootstrap) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            return admin.describeTopics(List.of(TOPIC)).allTopicNames().get().get(TOPIC).topicId().toString();
        }
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                batcher.onRevoked(partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                batcher.onAssigned(partitions);
            }
        });
        long backoffMs = 0, retryAtMs = 0;
        try {
            while (running) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(200))) {
                    batcher.accept(new TopicPartition(r.topic(), r.partition()), r.offset(), r.value(), System.currentTimeMillis());
                }
                long now = System.currentTimeMillis();
                if (now < retryAtMs) continue;
                try {
                    batcher.flushDue(now);
                    if (backoffMs > 0) {
                        log.info("ClickHouse writes recovered; resuming consumption");
                        consumer.resume(consumer.assignment());
                        backoffMs = 0;
                        lastError = null;
                    }
                } catch (WakeupException e) {
                    throw e;
                } catch (Exception e) {
                    backoffMs = Math.min(30_000, Math.max(1_000, backoffMs * 2));
                    retryAtMs = now + backoffMs;
                    lastError = e.getMessage();
                    metrics.counter("clickhouse_sink_insert_failures_total").increment();
                    log.warn("ClickHouse write failed, pausing consumption and retrying in {} ms: {}", backoffMs, e.getMessage());
                    consumer.pause(consumer.assignment());
                }
            }
        } catch (WakeupException e) {
            if (running) throw e;
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    public Map<String, Object> status() {
        return Map.of("pendingBatches", batcher.pendingBatches(), "lastError", lastError == null ? "" : lastError);
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
    }
}
