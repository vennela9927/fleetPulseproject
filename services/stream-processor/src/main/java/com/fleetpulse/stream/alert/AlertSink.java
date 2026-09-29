package com.fleetpulse.stream.alert;

import com.fleetpulse.common.event.Json;
import com.fleetpulse.stream.sink.VehicleDirectory;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Stores alerts in Postgres and announces new ones on Redis pub/sub ({@code alerts:{tenant}})
 * for the dashboard's live feed.
 *
 * <p>Idempotent: the insert is {@code ON CONFLICT (dedup_key) DO NOTHING}, and only rows that
 * were actually inserted are announced, so a replay after a crash neither duplicates an alert
 * nor re-notifies anyone. Offsets are committed after the insert (at-least-once delivery,
 * exactly-once effect).
 *
 * <p>Records {@code alert_latency}: vehicle timestamp of the triggering event to the alert
 * being stored, the delay a fleet manager actually experiences.
 */
public final class AlertSink implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AlertSink.class);

    private final KafkaConsumer<String, byte[]> consumer;
    private final JdbcTemplate db;
    private final VehicleDirectory vehicles;
    private final RedisClient redis;
    private final StatefulRedisConnection<String, String> conn;
    private final MeterRegistry metrics;
    private final Timer latency;
    private final Clock clock;
    private volatile boolean running = true;

    public AlertSink(String bootstrap, JdbcTemplate db, VehicleDirectory vehicles, String redisUrl,
                     MeterRegistry metrics, Clock clock) {
        this.db = db;
        this.vehicles = vehicles;
        this.metrics = metrics;
        this.clock = clock;
        this.latency = Timer.builder("alert_latency")
                .description("vehicle event time to alert stored")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(metrics);
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "alert-sink");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumer = new KafkaConsumer<>(p);
        redis = RedisClient.create(redisUrl);
        conn = redis.connect();
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(AlertTopology.ALERTS));
        try {
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
                if (records.isEmpty()) continue;
                store(records);
                consumer.commitSync();
            }
        } catch (WakeupException e) {
            if (running) throw e;
        } catch (Exception e) {
            log.error("alert sink stopped", e);
            throw e;
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private void store(ConsumerRecords<String, byte[]> records) {
        List<Alert> alerts = new ArrayList<>();
        List<VehicleDirectory.Vehicle> owners = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> r : records) {
            Alert a = Json.read(r.value(), Alert.class);
            VehicleDirectory.Vehicle v = vehicles.get(a.vin());
            if (v == null) {
                metrics.counter("alert_sink_skipped_total", "reason", "unregistered_vin").increment();
                continue;
            }
            alerts.add(a);
            owners.add(v);
            rows.add(new Object[]{v.tenantId(), v.id(), a.rule().name(), a.dedupKey(),
                    Timestamp.from(a.openedAt()), new String(Json.write(details(a)))});
        }
        if (rows.isEmpty()) return;
        int[] inserted = db.batchUpdate("""
                INSERT INTO alert (tenant_id, vehicle_id, rule_code, dedup_key, opened_at, details)
                VALUES (?::uuid, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (dedup_key) DO NOTHING""", rows);

        long now = clock.millis();
        for (int i = 0; i < inserted.length; i++) {
            if (inserted[i] == 0) {
                metrics.counter("alert_sink_duplicates_total").increment();
                continue;
            }
            Alert a = alerts.get(i);
            latency.record(Duration.ofMillis(Math.max(0, now - a.eventTs().toEpochMilli())));
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("dedupKey", a.dedupKey());
            msg.put("vin", a.vin());
            msg.put("vehicleId", owners.get(i).id());
            msg.put("rule", a.rule().name());
            msg.put("severity", a.severity());
            msg.put("openedAt", a.openedAt().toString());
            msg.put("details", a.details());
            conn.async().publish("alerts:" + owners.get(i).tenantId(), new String(Json.write(msg)));
        }
    }

    /** The rule's own details plus the event and detection times, for the vehicle page. */
    private static Map<String, Object> details(Alert a) {
        Map<String, Object> d = new LinkedHashMap<>(a.details());
        d.put("severity", a.severity());
        d.put("event_ts", a.eventTs().toString());
        if (a.ingestTs() != null) d.put("ingest_ts", a.ingestTs().toString());
        d.put("detected_at", a.detectedAt().toString());
        return d;
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        conn.close();
        redis.shutdown();
    }
}
