package com.fleetpulse.normalizer;

import com.fleetpulse.common.mapping.MappingRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

@SpringBootApplication
@RestController
public class NormalizerApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NormalizerApplication.class);

    private final MeterRegistry metrics;
    private final String bootstrap;
    private final int threads;
    private final int replication;
    private final String stateDir;
    private MappingRegistry registry;
    private DlqReplayer replayer;
    private KafkaStreams streams;

    public NormalizerApplication(MeterRegistry metrics,
                                 @Value("${normalizer.kafka-bootstrap}") String bootstrap,
                                 @Value("${normalizer.stream-threads}") int threads,
                                 @Value("${normalizer.replication-factor}") int replication,
                                 @Value("${normalizer.state-dir}") String stateDir) {
        this.metrics = metrics;
        this.bootstrap = bootstrap;
        this.threads = threads;
        this.replication = replication;
        this.stateDir = stateDir;
    }

    public static void main(String[] args) {
        SpringApplication.run(NormalizerApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        registry = new MappingRegistry();
        replayer = new DlqReplayer(bootstrap);
        // Mappings present at startup are already live. The listener is registered after the
        // initial load, so only versions activated at runtime (onboarding) trigger a replay.
        registry.start(bootstrap, Duration.ofSeconds(30));
        registry.onActivated((oem, mapper) -> {
            log.info("{} v{} activated at runtime; replaying parked events", oem, mapper.spec().version());
            replayer.replay(oem, mapper);
        });

        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "normalizer");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        p.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, threads);
        p.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, replication);
        p.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 200);
        p.put(StreamsConfig.producerPrefix("compression.type"), "lz4");
        p.put(StreamsConfig.producerPrefix("linger.ms"), 10);
        p.put(StreamsConfig.consumerPrefix("max.poll.records"), 2_000);

        streams = new KafkaStreams(NormalizerTopology.build(registry, metrics, Clock.systemUTC()), p);
        // A poison record or transient error replaces the thread instead of killing the app.
        streams.setUncaughtExceptionHandler(e -> {
            log.error("stream thread failed; replacing it", e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
        new KafkaStreamsMetrics(streams).bindTo(metrics);
        streams.start();
        log.info("normalizer started with mappings {}", registry.versions());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("state", streams == null ? "STARTING" : streams.state().name(),
                "mappings", registry == null ? Map.of() : registry.versions(),
                "replays", replayer == null ? Map.of() : replayer.progress());
    }

    @PreDestroy
    void stop() {
        if (streams != null) streams.close(Duration.ofSeconds(10));
        if (replayer != null) replayer.close();
        if (registry != null) registry.close();
    }
}
