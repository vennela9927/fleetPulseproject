package com.fleetpulse.stream.live;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.stream.sink.VehicleDirectory;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Keeps each vehicle's latest state in Redis for the live map and vehicle page:
 * <ul>
 *   <li>{@code v:{vin}}: a hash of the latest reading, plus owner and derived status;</li>
 *   <li>{@code geo:{tenant}}: a GEO index per tenant, so the map asks for "vehicles in this
 *       viewport" and the tenant boundary is part of the key, not a filter.</li>
 * </ul>
 * At-least-once: offsets auto-commit only for records whose writes completed. Replays and
 * out-of-order events are harmless because the Lua script applies an event only if it is
 * newer than the stored one, so the state converges to the latest reading whatever order
 * events arrive in. Redis holds no data of record: it can be flushed and rebuilt from Kafka.
 */
public final class LiveStateSink implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveStateSink.class);

    /** KEYS: hash, geo set. ARGV: ts, lon, lat, vin, then field/value pairs. */
    static final String UPSERT_IF_NEWER = """
            local cur = tonumber(redis.call('HGET', KEYS[1], 'ts') or '0')
            if tonumber(ARGV[1]) <= cur then return 0 end
            redis.call('HSET', KEYS[1], 'ts', ARGV[1], unpack(ARGV, 5))
            redis.call('GEOADD', KEYS[2], ARGV[2], ARGV[3], ARGV[4])
            return 1
            """;

    private final KafkaConsumer<String, byte[]> consumer;
    private final RedisClient redis;
    private final StatefulRedisConnection<String, String> conn;
    private final VehicleDirectory vehicles;
    private final MeterRegistry metrics;
    private final String scriptSha;
    private volatile boolean running = true;

    public LiveStateSink(String bootstrap, String redisUrl, VehicleDirectory vehicles, MeterRegistry metrics) {
        this.vehicles = vehicles;
        this.metrics = metrics;
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "live-state");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumer = new KafkaConsumer<>(p);
        redis = RedisClient.create(redisUrl);
        conn = redis.connect();
        scriptSha = conn.sync().scriptLoad(UPSERT_IF_NEWER);
    }

    @Override
    public void run() {
        consumer.subscribe(List.of("telemetry.canonical"));
        RedisAsyncCommands<String, String> cmd = conn.async();
        conn.setAutoFlushCommands(false);   // pipeline each poll's writes into one round trip
        try {
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) continue;
                List<RedisFuture<?>> pending = new ArrayList<>(records.count());
                for (ConsumerRecord<String, byte[]> r : records) {
                    CanonicalEvent e;
                    try {
                        e = Json.read(r.value(), CanonicalEvent.class);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    VehicleDirectory.Vehicle v = vehicles.get(e.vin());
                    if (v == null) {
                        metrics.counter("live_state_skipped_total", "reason", "unregistered_vin").increment();
                        continue;
                    }
                    pending.add(cmd.evalsha(scriptSha, ScriptOutputType.INTEGER, keys(e, v), args(e, v)));
                }
                conn.flushCommands();
                if (!LettuceFutures.awaitAll(30, TimeUnit.SECONDS, pending.toArray(new RedisFuture[0]))) {
                    throw new IllegalStateException("Redis writes did not complete in 30 s");
                }
                metrics.counter("live_state_updates_total").increment(pending.size());
            }
        } catch (WakeupException e) {
            if (running) throw e;
        } catch (Exception e) {
            // Offsets for this poll are not committed, so the records are re-read on restart.
            log.error("live state sink stopped", e);
            throw e;
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    static String[] keys(CanonicalEvent e, VehicleDirectory.Vehicle v) {
        return new String[]{"v:" + e.vin(), "geo:" + v.tenantId()};
    }

    static String[] args(CanonicalEvent e, VehicleDirectory.Vehicle v) {
        String status = !e.engineOn() ? "OFF" : e.speedKmh() >= 1 ? "DRIVING" : "IDLING";
        return new String[]{
                Long.toString(e.ts().toEpochMilli()), Double.toString(e.lon()), Double.toString(e.lat()), e.vin(),
                "tenant", v.tenantId(),
                "fleet", Long.toString(v.fleetId()),
                "vehicle_id", Long.toString(v.id()),
                "oem", e.oem(),
                "powertrain", v.powertrain(),
                "status", status,
                "lat", Double.toString(e.lat()),
                "lon", Double.toString(e.lon()),
                "speed_kmh", Double.toString(e.speedKmh()),
                "odo_km", Double.toString(e.odoKm()),
                "fuel_pct", str(e.fuelPct()),
                "soc_pct", str(e.socPct()),
                "coolant_c", str(e.coolantC()),
                "batt_v", str(e.battV()),
                "dtc", String.join(",", e.dtc()),
                "evt", e.evt().name()};
    }

    private static String str(Double d) {
        return d == null ? "" : Double.toString(d);
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        conn.close();
        redis.shutdown();
    }
}
