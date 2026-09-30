package com.fleetpulse.stream;

import com.fleetpulse.stream.alert.AlertSink;
import com.fleetpulse.stream.alert.AlertTopology;
import com.fleetpulse.stream.alert.RuleEngine;
import com.fleetpulse.stream.live.LiveStateSink;
import com.fleetpulse.stream.sink.ClickHouseSink;
import com.fleetpulse.stream.sink.VehicleDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Four independent parts, each with its own consumer group so one falling behind never
 * delays another:
 * <pre>
 *  telemetry.canonical ─┬─► alert detector (Kafka Streams, exactly-once) ──► alerts ──► alert sink ──► Postgres + Redis pub/sub
 *                       ├─► ClickHouse sink (exactly-once batches) ─────────────────────────────────► fleet.telemetry
 *                       └─► live state sink (latest-wins) ──────────────────────────────────────────► Redis
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(StreamProps.class)
@EnableScheduling
@RestController
public class StreamProcessorApplication implements CommandLineRunner, HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessorApplication.class);

    private final JdbcTemplate db;
    private final MeterRegistry metrics;
    private final StreamProps props;
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private VehicleDirectory vehicles;
    private KafkaStreams streams;
    private final List<ClickHouseSink> clickhouse = new ArrayList<>();

    public StreamProcessorApplication(JdbcTemplate db, MeterRegistry metrics, StreamProps props) {
        this.db = db;
        this.metrics = metrics;
        this.props = props;
    }

    public static void main(String[] args) {
        SpringApplication.run(StreamProcessorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        Clock clock = Clock.systemUTC();
        vehicles = new VehicleDirectory(db, clock);
        vehicles.reload();
        Set<String> criticalDtcs = new HashSet<>(db.queryForList("SELECT code FROM dtc_code WHERE severity = 5", String.class));
        log.info("critical fault codes: {}", criticalDtcs);

        streams = new KafkaStreams(AlertTopology.build(new RuleEngine(criticalDtcs, clock), metrics), streamsConfig());
        streams.setUncaughtExceptionHandler(e -> {
            log.error("alert stream thread failed; replacing it", e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
        new KafkaStreamsMetrics(streams).bindTo(metrics);
        streams.start();

        start("alert-sink", new AlertSink(props.kafkaBootstrap(), db, vehicles, props.redisUrl(), metrics, clock));
        // One live-state consumer: Redis runs commands on one thread, and four pipelining consumers
        // overloaded it (30 s timeouts). The ClickHouse sink is different: one consumer did one ~1 s
        // insert at a time and fell behind, so several split the partitions and insert in parallel.
        // Batches are per partition (ADR 0001), so exactly-once holds whichever consumer owns one.
        start("live-state-sink", new LiveStateSink(props.kafkaBootstrap(), props.redisUrl(), vehicles, metrics));
        for (int i = 0; i < Math.max(1, props.clickhouseSinks()); i++) {
            ClickHouseSink sink = new ClickHouseSink(props.kafkaBootstrap(), props.clickhouseUrl(), props.clickhouseUser(),
                    props.clickhousePassword(), vehicles, metrics, props.clickhouseBatchRows(), props.clickhouseFlushMs());
            clickhouse.add(sink);
            start("clickhouse-sink-" + i, sink);
        }
        log.info("stream processor started: {} vehicles known", vehicles.size());
    }

    private Properties streamsConfig() {
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "alert-detector");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap());
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        p.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, props.streamThreads());
        p.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, props.replicationFactor());
        p.put(StreamsConfig.STATE_DIR_CONFIG, props.stateDir());
        // Alerts become visible to consumers when the transaction commits, so this bounds
        // the detector's contribution to alert latency.
        p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        p.put(StreamsConfig.producerPrefix("linger.ms"), 5);
        p.put(StreamsConfig.consumerPrefix("max.poll.records"), 2_000);
        return p;
    }

    private <T extends Runnable & AutoCloseable> void start(String name, T worker) {
        closeables.add(worker);
        Thread t = new Thread(() -> {
            try {
                worker.run();
            } catch (Exception e) {
                // A dead sink must be visible: health() reports DOWN and the orchestrator
                // restarts the service, which resumes from committed offsets.
                log.error("{} died", name, e);
                failed.add(name);
            }
        }, name);
        t.start();
    }

    /** Picks up newly registered vehicles even when none of them has reported yet. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void refreshVehicles() {
        if (vehicles != null) vehicles.reload();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("alertDetector", streams == null ? "STARTING" : streams.state().name());
        s.put("vehiclesKnown", vehicles == null ? 0 : vehicles.size());
        s.put("clickhouseSink", clickhouse.stream().map(ClickHouseSink::status).toList());
        s.put("failedWorkers", failed);
        return s;
    }

    @Override
    public Health health() {
        KafkaStreams.State state = streams == null ? null : streams.state();
        boolean detectorDown = state == KafkaStreams.State.ERROR || state == KafkaStreams.State.NOT_RUNNING;
        Health.Builder h = failed.isEmpty() && !detectorDown ? Health.up() : Health.down();
        return h.withDetail("alertDetector", state == null ? "STARTING" : state.name())
                .withDetail("failedWorkers", failed).build();
    }

    @PreDestroy
    void stop() {
        if (streams != null) streams.close(Duration.ofSeconds(10));
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("error closing {}", c, e);
            }
        }
    }
}
