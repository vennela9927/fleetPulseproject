package com.fleetpulse.gateway;

import com.fleetpulse.gateway.GatewayApplication.GatewayProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Durable hand-off to {@code telemetry.raw}: acks=all and an idempotent producer. */
public interface RawPublisher {

    record Raw(String vin, byte[] payload) {}

    /** False when too many records are waiting for broker acknowledgement (back-pressure). */
    boolean hasCapacity(int records);

    /**
     * Publishes a batch and blocks until every record is acknowledged by the in-sync replicas.
     * Returning normally means the batch is durable; throwing means the caller must retry.
     */
    void publishAndAwait(String oem, String requestId, List<Raw> records) throws Exception;

    @Component
    @Profile("!test")
    class Kafka implements RawPublisher {

        static final String TOPIC = "telemetry.raw";
        private final KafkaProducer<byte[], byte[]> producer;
        private final long maxPending;
        private final AtomicLong pending = new AtomicLong();

        Kafka(GatewayProperties props, MeterRegistry metrics) {
            Properties p = new Properties();
            p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap());
            p.put(ProducerConfig.ACKS_CONFIG, "all");
            p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
            p.put(ProducerConfig.BATCH_SIZE_CONFIG, 128 * 1024);
            p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
            p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);          // never hang an HTTP thread on a dead broker
            p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
            p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
            this.producer = new KafkaProducer<>(p, new ByteArraySerializer(), new ByteArraySerializer());
            this.maxPending = props.maxPendingRecords();
            Gauge.builder("gateway_pending_records", pending, AtomicLong::get).register(metrics);
        }

        @Override
        public boolean hasCapacity(int records) {
            return pending.get() + records <= maxPending;
        }

        @Override
        public void publishAndAwait(String oem, String requestId, List<Raw> records) throws Exception {
            byte[] oemBytes = oem.getBytes(StandardCharsets.UTF_8);
            byte[] reqBytes = requestId.getBytes(StandardCharsets.UTF_8);
            byte[] received = Instant.now().toString().getBytes(StandardCharsets.UTF_8);
            List<Future<RecordMetadata>> futures = new ArrayList<>(records.size());
            pending.addAndGet(records.size());
            try {
                for (Raw r : records) {
                    var rec = new ProducerRecord<>(TOPIC,
                            r.vin() == null ? null : r.vin().getBytes(StandardCharsets.UTF_8), r.payload());
                    rec.headers().add("oem", oemBytes).add("request_id", reqBytes).add("received_at", received);
                    futures.add(producer.send(rec));
                }
                for (Future<RecordMetadata> f : futures) f.get(25, TimeUnit.SECONDS);
            } finally {
                pending.addAndGet(-records.size());
            }
        }

        @PreDestroy
        void close() {
            producer.close();
        }
    }
}
