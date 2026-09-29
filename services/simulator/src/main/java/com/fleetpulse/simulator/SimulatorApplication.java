package com.fleetpulse.simulator;

import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.EventMapper;
import com.fleetpulse.common.mapping.MappingSpec;
import com.fleetpulse.simulator.backfill.Backfiller;
import com.fleetpulse.simulator.fleet.Seeder;
import com.fleetpulse.simulator.model.FaultPlan;
import com.fleetpulse.simulator.run.HttpSink;
import com.fleetpulse.simulator.run.KafkaSink;
import com.fleetpulse.simulator.run.LiveRunner;
import com.fleetpulse.simulator.run.Sink;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@EnableConfigurationProperties(SimProperties.class)
public class SimulatorApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorApplication.class);

    private final SimProperties props;
    private final Seeder seeder;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private volatile LiveRunner runner;

    public SimulatorApplication(SimProperties props, Seeder seeder, JdbcTemplate jdbc, MeterRegistry metrics) {
        this.props = props;
        this.seeder = seeder;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        List<String> modes = props.modes();
        log.info("simulator modes: {}", modes);
        if (modes.contains("seed")) seeder.seed();
        if (modes.contains("backfill") || modes.contains("run")) {
            var fleet = seeder.loadFleet(props.vehicles());
            if (fleet.isEmpty()) throw new IllegalStateException("no vehicles in the database; run with mode 'seed' first");
            if (modes.contains("backfill")) new Backfiller(props, jdbc).run(fleet);
            if (modes.contains("run")) {
                runner = new LiveRunner(props, sink(), fleet, metrics);
                Thread t = new Thread(runner, "live-runner");
                t.setDaemon(false);
                t.start();
            }
        }
    }

    private Sink sink() throws Exception {
        if ("kafka".equalsIgnoreCase(props.sink())) {
            // The load-test path needs the VIN for the partition key, so reuse the mappings.
            Map<String, EventMapper> mappers = new HashMap<>();
            for (String name : List.of("aurora", "borealis", "cygnus")) {
                try (InputStream in = getClass().getResourceAsStream("/mappings/" + name + ".json")) {
                    MappingSpec spec = Json.MAPPER.readValue(in, MappingSpec.class);
                    mappers.put(spec.oem(), EventMapper.compile(spec));
                }
            }
            return new KafkaSink(props.kafkaBootstrap(), payload -> {
                for (EventMapper m : mappers.values()) {
                    String vin = m.vinOf(payload);
                    if (vin != null) return vin;
                }
                return null;
            });
        }
        return new HttpSink(props.gatewayUrl(), props.apiKey(), props.maxInFlight(), metrics);
    }

    LiveRunner runner() {
        return runner;
    }

    /** Test and demo controls. Exposed only on the internal network; the chaos panel calls these via the API. */
    @RestController
    @RequestMapping("/admin")
    static class AdminController {
        private final SimulatorApplication app;

        AdminController(SimulatorApplication app) {
            this.app = app;
        }

        @GetMapping("/stats")
        ResponseEntity<?> stats() {
            LiveRunner r = app.runner();
            return r == null ? ResponseEntity.status(503).body(Map.of("error", "live run not started"))
                    : ResponseEntity.ok(r.stats());
        }

        @PostMapping("/burst")
        ResponseEntity<?> burst(@RequestParam(defaultValue = "3") int factor,
                                @RequestParam(defaultValue = "300") int seconds) {
            LiveRunner r = app.runner();
            if (r == null) return ResponseEntity.status(503).build();
            r.burst(factor, Duration.ofSeconds(seconds));
            return ResponseEntity.ok(Map.of("factor", factor, "seconds", seconds));
        }

        @PostMapping("/pause")
        ResponseEntity<?> pause(@RequestParam boolean paused) {
            LiveRunner r = app.runner();
            if (r == null) return ResponseEntity.status(503).build();
            r.pause(paused);
            return ResponseEntity.ok(Map.of("paused", paused));
        }

        @PostMapping("/inject")
        ResponseEntity<?> inject(@RequestParam FaultPlan.Component component,
                                 @RequestParam(defaultValue = "10") int count,
                                 @RequestParam(defaultValue = "5") int minutes,
                                 @RequestParam(required = false) String tenant) {
            LiveRunner r = app.runner();
            if (r == null) return ResponseEntity.status(503).build();
            List<String> vins = r.inject(component, Math.min(count, 500), Duration.ofMinutes(minutes), tenant);
            return ResponseEntity.ok(Map.of("component", component, "vins", vins));
        }

        @PostMapping("/sensor-fault")
        ResponseEntity<?> sensorFault(@RequestParam(defaultValue = "5") int count,
                                      @RequestParam(defaultValue = "10") int minutes,
                                      @RequestParam(required = false) String tenant) {
            LiveRunner r = app.runner();
            if (r == null) return ResponseEntity.status(503).build();
            return ResponseEntity.ok(Map.of("vins", r.breakCoolantSensors(Math.min(count, 100),
                    Duration.ofMinutes(minutes), tenant)));
        }

        @PostMapping("/firmware")
        ResponseEntity<?> firmware(@RequestParam String oem, @RequestParam boolean mph) {
            LiveRunner r = app.runner();
            if (r == null) return ResponseEntity.status(503).build();
            try {
                r.speedMph(oem, mph);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            return ResponseEntity.ok(Map.of("oem", oem, "speedInMph", mph));
        }

        /** What a mechanic finds: the component actually failing on each VIN ("" when healthy). */
        @PostMapping("/inspect")
        ResponseEntity<?> inspect(@org.springframework.web.bind.annotation.RequestBody List<String> vins) {
            LiveRunner r = app.runner();
            if (r == null) return ResponseEntity.status(503).build();
            return ResponseEntity.ok(r.inspect(vins.subList(0, Math.min(vins.size(), 2000))));
        }
    }
}
