package com.fleetpulse.simulator.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetpulse.common.event.Json;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * Writes raw payloads straight to {@code telemetry.raw}, bypassing the gateway.
 * Used by the load test to measure broker and stream-processor throughput in isolation.
 * The partition key is taken from the payload by the caller-supplied VIN extractor.
 */
public final class KafkaSink implements Sink {

    private final KafkaProducer<byte[], byte[]> producer;
    private final java.util.function.Function<ObjectNode, String> vinOf;

    public KafkaSink(String bootstrap, java.util.function.Function<ObjectNode, String> vinOf) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 128L * 1024 * 1024);
        this.producer = new KafkaProducer<>(p, new ByteArraySerializer(), new ByteArraySerializer());
        this.vinOf = vinOf;
    }

    @Override
    public void send(String oem, List<ObjectNode> batch) {
        byte[] oemHeader = oem.getBytes(StandardCharsets.UTF_8);
        for (ObjectNode payload : batch) {
            String vin = vinOf.apply(payload);
            ProducerRecord<byte[], byte[]> rec = new ProducerRecord<>("telemetry.raw",
                    vin == null ? null : vin.getBytes(StandardCharsets.UTF_8), Json.write(payload));
            rec.headers().add("oem", oemHeader);
            producer.send(rec);   // blocks when buffer.memory is full: back-pressure
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
