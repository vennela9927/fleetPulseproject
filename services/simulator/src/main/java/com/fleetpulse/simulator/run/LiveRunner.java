package com.fleetpulse.simulator.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetpulse.simulator.SimProperties;
import com.fleetpulse.simulator.fleet.Catalog;
import com.fleetpulse.simulator.fleet.Seeder.FleetVehicle;
import com.fleetpulse.simulator.format.OemFormatter;
import com.fleetpulse.simulator.model.FaultPlan;
import com.fleetpulse.simulator.model.VehicleSim;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams live telemetry for the whole fleet.
 *
 * <p>Time is split into 200 ms ticks; each vehicle belongs to one tick slot, so the load is
 * spread evenly (100K vehicles every 10 s = 2,000 events per tick) instead of arriving in
 * one spike. A burst of factor k steps every vehicle k times per interval.
 *
 * <p>Real-world imperfections are injected on purpose: duplicates, out-of-order delivery
 * and corrupted payloads. The runner keeps a ledger of valid, unique events per OEM, which
 * the dashboard compares with what reached storage to prove nothing was lost.
 */
public final class LiveRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(LiveRunner.class);
    private static final int TICKS_PER_SECOND = 5;

    private record Delayed(long releaseAtMs, String oem, ObjectNode payload) {}

    private final SimProperties props;
    private final Sink sink;
    private final VehicleSim[] fleet;
    private final SplittableRandom chaos = new SplittableRandom(42);
    private final PriorityQueue<Delayed> delayed = new PriorityQueue<>((a, b) -> Long.compare(a.releaseAtMs, b.releaseAtMs));
    private final Map<String, AtomicLong> ledger = new ConcurrentHashMap<>();
    private final Counter duplicates, invalid, outOfOrder, events;
    private final AtomicLong tickLagMs = new AtomicLong();
    private volatile int burstFactor = 1;
    private volatile Instant burstUntil = Instant.EPOCH;
    private volatile boolean running = true;

    public LiveRunner(SimProperties props, Sink sink, List<FleetVehicle> vehicles, MeterRegistry metrics) {
        this.props = props;
        this.sink = sink;
        Instant now = Instant.now();
        Instant anchor = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        long firstSeq = now.getEpochSecond();   // monotonic across simulator restarts
        this.fleet = new VehicleSim[vehicles.size()];
        for (int i = 0; i < vehicles.size(); i++) {
            FleetVehicle fv = vehicles.get(i);
            Catalog.Model m = Catalog.byId(fv.modelId());
            VehicleSim v = new VehicleSim(fv.vin(), m, fv.lat(), fv.lon(), now, firstSeq);
            v.schedule(FaultPlan.forVehicle(fv.vin(), m.powertrain(), anchor));
            fleet[i] = v;
        }
        this.duplicates = metrics.counter("sim_duplicates_sent_total");
        this.invalid = metrics.counter("sim_invalid_sent_total");
        this.outOfOrder = metrics.counter("sim_out_of_order_sent_total");
        this.events = metrics.counter("sim_events_generated_total");
        Gauge.builder("sim_tick_lag_ms", tickLagMs, AtomicLong::get).register(metrics);
        Gauge.builder("sim_burst_factor", () -> burstFactor).register(metrics);
        for (String oem : List.of("AURORA", "BOREALIS", "CYGNUS", "DRACO")) {
            AtomicLong a = ledger.computeIfAbsent(oem, k -> new AtomicLong());
            Gauge.builder("sim_ledger_valid_unique", a, AtomicLong::get).tag("oem", oem).register(metrics);
        }
    }

    @Override
    public void run() {
        int slots = props.intervalSeconds() * TICKS_PER_SECOND;
        long tickMs = 1000 / TICKS_PER_SECOND;
        long next = System.currentTimeMillis();
        long tick = 0;
        log.info("live: streaming {} vehicles every {} s via {}", fleet.length, props.intervalSeconds(), props.sink());
        while (running) {
            try {
                int k = Instant.now().isBefore(burstUntil) ? burstFactor : 1;
                double dt = (double) props.intervalSeconds() / k;
                Map<String, List<ObjectNode>> batches = new HashMap<>();
                Instant now = Instant.now();
                for (int f = 0; f < k; f++) {
                    int slot = (int) ((tick * k + f) % slots);
                    for (int i = slot; i < fleet.length; i += slots) emit(fleet[i], now, dt, batches);
                }
                releaseDelayed(batches);
                for (var e : batches.entrySet()) flush(e.getKey(), e.getValue());
                tick++;
                next += tickMs;
                long sleep = next - System.currentTimeMillis();
                tickLagMs.set(Math.max(0, -sleep));
                if (sleep > 0) Thread.sleep(sleep);
                else if (-sleep > 5_000) next = System.currentTimeMillis();   // far behind: do not try to catch up
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("live tick failed", e);
            }
        }
    }

    private void emit(VehicleSim v, Instant now, double dt, Map<String, List<ObjectNode>> batches) throws InterruptedException {
        VehicleSim.Sample s = v.step(now, dt);
        ObjectNode payload = OemFormatter.format(s);
        String oem = s.oem();
        ledger.get(oem).incrementAndGet();
        events.increment();

        if (chaos.nextDouble() < props.outOfOrderRate()) {
            delayed.add(new Delayed(System.currentTimeMillis() + 5_000 + chaos.nextInt(25_000), oem, payload));
            outOfOrder.increment();
        } else {
            add(batches, oem, payload);
        }
        if (chaos.nextDouble() < props.duplicateRate()) {
            add(batches, oem, payload.deepCopy());
            duplicates.increment();
        }
        if (chaos.nextDouble() < props.invalidRate()) {
            add(batches, oem, corrupt(payload.deepCopy(), oem));
            invalid.increment();
        }
    }

    private void add(Map<String, List<ObjectNode>> batches, String oem, ObjectNode p) throws InterruptedException {
        List<ObjectNode> b = batches.computeIfAbsent(oem, k -> new ArrayList<>(props.batchSize()));
        b.add(p);
        if (b.size() >= props.batchSize()) flush(oem, b);
    }

    private void flush(String oem, List<ObjectNode> batch) throws InterruptedException {
        if (batch.isEmpty()) return;
        sink.send(oem, new ArrayList<>(batch));
        batch.clear();
    }

    private void releaseDelayed(Map<String, List<ObjectNode>> batches) throws InterruptedException {
        long nowMs = System.currentTimeMillis();
        while (!delayed.isEmpty() && delayed.peek().releaseAtMs <= nowMs) {
            Delayed d = delayed.poll();
            add(batches, d.oem, d.payload);
        }
    }

    /** Breaks a payload the way real feeds break: bad VIN check digit, or a 0,0 GPS fix. */
    private ObjectNode corrupt(ObjectNode p, String oem) {
        boolean badVin = chaos.nextBoolean();
        switch (oem) {
            case "AURORA" -> { if (badVin) p.put("vin", "AUR00000000000000"); else p.putObject("location").put("lat", 0).put("lng", 0); }
            case "BOREALIS" -> { if (badVin) p.putObject("vehicle").put("id", "BRL00000000000000"); else p.putArray("gps").add(0).add(0); }
            case "CYGNUS" -> p.put("deviceVin", "CYG00000000000000");
            default -> p.put("sequence", -1);
        }
        return p;
    }

    // ------------------------------------------------------------------ admin hooks

    public void burst(int factor, Duration duration) {
        burstFactor = Math.max(1, Math.min(10, factor));
        burstUntil = Instant.now().plus(duration);
        log.warn("live: burst x{} for {}", burstFactor, duration);
    }

    /** Injects a fast-developing fault into {@code count} compatible vehicles; returns their VINs. */
    public List<String> inject(FaultPlan.Component component, int count, Duration over) {
        List<String> vins = new ArrayList<>();
        SplittableRandom r = new SplittableRandom();
        Instant now = Instant.now();
        for (int attempts = 0; vins.size() < count && attempts < count * 50; attempts++) {
            VehicleSim v = fleet[r.nextInt(fleet.length)];
            if (v.model.oem().equals("DRACO") || !component.appliesTo(v.model.powertrain())) continue;
            v.inject(component, now, over);
            vins.add(v.vin);
        }
        log.warn("live: injected {} into {} vehicles", component, vins.size());
        return vins;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vehicles", fleet.length);
        m.put("intervalSeconds", props.intervalSeconds());
        m.put("burstFactor", Instant.now().isBefore(burstUntil) ? burstFactor : 1);
        m.put("burstUntil", burstUntil.toString());
        Map<String, Long> l = new LinkedHashMap<>();
        ledger.forEach((k, v) -> l.put(k, v.get()));
        m.put("ledgerValidUnique", l);
        m.put("duplicatesSent", (long) duplicates.count());
        m.put("invalidSent", (long) invalid.count());
        m.put("outOfOrderSent", (long) outOfOrder.count());
        m.put("delayedPending", delayed.size());
        m.put("tickLagMs", tickLagMs.get());
        if (sink instanceof HttpSink h) m.put("httpInFlight", h.inFlight());
        return m;
    }

    public void stop() {
        running = false;
    }
}
