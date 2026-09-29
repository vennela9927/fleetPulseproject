package com.fleetpulse.simulator.backfill;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.simulator.SimProperties;
import com.fleetpulse.simulator.fleet.Catalog;
import com.fleetpulse.simulator.fleet.Seeder.FleetVehicle;
import com.fleetpulse.simulator.model.FaultPlan;
import com.fleetpulse.simulator.model.VehicleSim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Generates historical telemetry for the failure-prediction model: {@code backfillDays}
 * of operating-hours samples per vehicle, bulk-loaded into ClickHouse as gzipped
 * JSONEachRow over HTTP (no driver, ~1M rows/s), plus the breakdowns and repairs that
 * actually happened, written to Postgres as training labels.
 *
 * <p>This is a historical import, so it bypasses Kafka on purpose. Draco vehicles are
 * skipped: that OEM has not been onboarded yet.
 */
public final class Backfiller {

    private static final Logger log = LoggerFactory.getLogger(Backfiller.class);
    private static final DateTimeFormatter CH_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    // Each row costs ~1.4 KB of ClickHouse memory while it is parsed and pushed through the
    // rollup views; 150K rows keeps two concurrent inserts well inside the 1.5 GB container.
    private static final int ROWS_PER_INSERT = 150_000;
    // Vehicles operate 08:00-20:00 IST = 02:30-14:30 UTC.
    private static final int OPEN_MINUTE_UTC = 150, CLOSE_MINUTE_UTC = 870;

    private final SimProperties props;
    private final JdbcTemplate postgres;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicLong rows = new AtomicLong();

    public Backfiller(SimProperties props, JdbcTemplate postgres) {
        this.props = props;
        this.postgres = postgres;
    }

    public void run(List<FleetVehicle> fleet) throws Exception {
        Long existing = chCount();
        if (existing != null && existing > 0) {
            log.info("backfill: ClickHouse already holds {} rows, skipping", existing);
            return;
        }
        Instant anchor = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant start = anchor.minus(Duration.ofDays(props.backfillDays()));
        List<FleetVehicle> eligible = fleet.stream().filter(v -> v.modelId() != 8).toList();
        long t0 = System.currentTimeMillis();

        int threads = Math.max(1, props.backfillThreads());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int shard = t;
            jobs.add(pool.submit(() -> {
                generate(eligible, shard, threads, start, anchor);
                return null;
            }));
        }
        for (Future<?> f : jobs) f.get();
        pool.shutdown();
        long ms = System.currentTimeMillis() - t0;
        log.info("backfill: {} telemetry rows for {} vehicles over {} days in {} s ({} rows/s)",
                rows.get(), eligible.size(), props.backfillDays(), ms / 1000, rows.get() * 1000 / Math.max(1, ms));

        writeMaintenanceEvents(eligible, start, anchor);
    }

    /**
     * Day-major order: every insert holds rows for a single day, so it lands in a single
     * ClickHouse partition as one large part. Vehicle-major order would scatter each insert
     * across all daily partitions and create many small parts that must then be merged.
     */
    private void generate(List<FleetVehicle> fleet, int shard, int shards, Instant start, Instant anchor) throws Exception {
        long stepSeconds = props.backfillSampleMinutes() * 60L;
        List<FleetVehicle> mine = new ArrayList<>();
        List<VehicleSim> sims = new ArrayList<>();
        for (int i = shard; i < fleet.size(); i += shards) {
            FleetVehicle fv = fleet.get(i);
            Catalog.Model m = Catalog.byId(fv.modelId());
            VehicleSim v = new VehicleSim(fv.vin(), m, fv.lat(), fv.lon(), start, start.getEpochSecond());
            v.schedule(FaultPlan.forVehicle(fv.vin(), m.powertrain(), anchor));
            mine.add(fv);
            sims.add(v);
        }
        for (Instant day = start; day.isBefore(anchor); day = day.plus(Duration.ofDays(1))) {
            Instant open = day.plusSeconds(OPEN_MINUTE_UTC * 60L), close = day.plusSeconds(CLOSE_MINUTE_UTC * 60L);
            String tokenPrefix = "backfill-" + day.getEpochSecond() + "-" + shard + "-";
            int chunkNo = 0;
            Chunk chunk = new Chunk();
            for (int i = 0; i < sims.size(); i++) {
                FleetVehicle fv = mine.get(i);
                Catalog.Model m = Catalog.byId(fv.modelId());
                for (Instant t = open; t.isBefore(close); t = t.plusSeconds(stepSeconds)) {
                    chunk.write(fv, m, sims.get(i).step(t, stepSeconds));
                }
                if (chunk.count >= ROWS_PER_INSERT) {
                    insert(chunk.finish(), tokenPrefix + chunkNo++);
                    rows.addAndGet(chunk.count);
                    chunk = new Chunk();
                }
            }
            if (chunk.count > 0) {
                insert(chunk.finish(), tokenPrefix + chunkNo);
                rows.addAndGet(chunk.count);
            }
        }
    }

    /** A gzipped JSONEachRow body being built. */
    private static final class Chunk {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 << 20);
        final JsonGenerator gen;
        int count;

        Chunk() throws IOException {
            gen = Json.MAPPER.getFactory().createGenerator(new GZIPOutputStream(bytes, 1 << 16), JsonEncoding.UTF8);
            gen.setRootValueSeparator(new com.fasterxml.jackson.core.io.SerializedString("\n"));
        }

        void write(FleetVehicle fv, Catalog.Model m, VehicleSim.Sample s) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("vin", s.vin());
            gen.writeStringField("tenant_id", fv.tenantId());
            gen.writeNumberField("fleet_id", fv.fleetId());
            gen.writeStringField("oem", s.oem());
            gen.writeStringField("powertrain", m.powertrain().name());
            gen.writeNumberField("seq", s.seq());
            String ts = CH_TS.format(s.ts());
            gen.writeStringField("ts", ts);
            gen.writeStringField("ingest_ts", ts);
            gen.writeNumberField("lat", s.lat());
            gen.writeNumberField("lon", s.lon());
            gen.writeNumberField("speed_kmh", s.speedKmh());
            gen.writeNumberField("odo_km", s.odoKm());
            gen.writeNumberField("engine_on", s.engineOn() ? 1 : 0);
            gen.writeNumberField("rpm", s.rpm());
            nullable(gen, "fuel_pct", s.fuelPct());
            nullable(gen, "soc_pct", s.socPct());
            nullable(gen, "soh_pct", s.sohPct());
            nullable(gen, "coolant_c", s.coolantC());
            gen.writeNumberField("batt_v", s.battV());
            gen.writeArrayFieldStart("dtc");
            for (String c : s.dtc()) gen.writeString(c);
            gen.writeEndArray();
            gen.writeStringField("evt", s.evt().name());
            gen.writeEndObject();
            count++;
        }

        byte[] finish() throws IOException {
            gen.close();
            return bytes.toByteArray();
        }

        private static void nullable(JsonGenerator g, String name, Double v) throws IOException {
            if (v == null) g.writeNullField(name);
            else g.writeNumberField(name, v);
        }
    }

    /**
     * Inserts one batch. A failed insert can leave the batch in the raw table but not in a
     * rollup view (or the reverse), so a plain retry would double-count the rollups. The
     * deterministic token makes the retry skip every table that already holds the batch.
     */
    private void insert(byte[] gzippedRows, String dedupToken) throws Exception {
        String query = URLEncoder.encode("INSERT INTO fleet.telemetry FORMAT JSONEachRow", StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.clickhouseUrl() + "/?query=" + query
                        + "&insert_deduplicate=1&deduplicate_blocks_in_dependent_materialized_views=1"
                        + "&insert_deduplication_token=" + URLEncoder.encode(dedupToken, StandardCharsets.UTF_8)))
                .header("Content-Encoding", "gzip")
                .header("Authorization", basicAuth())
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzippedRows))
                .build();
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return;
            if (attempt >= 5) throw new IllegalStateException("ClickHouse insert failed: " + resp.body());
            log.warn("ClickHouse insert attempt {} failed ({}), retrying: {}", attempt, resp.statusCode(),
                    resp.body().lines().findFirst().orElse(""));
            Thread.sleep(2_000L * attempt);
        }
    }

    private Long chCount() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.clickhouseUrl() + "/?query="
                        + URLEncoder.encode("SELECT count() FROM fleet.telemetry", StandardCharsets.UTF_8)))
                .header("Authorization", basicAuth()).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalStateException("ClickHouse not reachable: " + resp.body());
        return Long.parseLong(resp.body().trim());
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (props.clickhouseUser() + ":" + props.clickhousePassword()).getBytes(StandardCharsets.UTF_8));
    }

    /** Breakdowns and the repair a day later, for every scheduled fault that fell in the past. */
    private void writeMaintenanceEvents(List<FleetVehicle> fleet, Instant start, Instant anchor) {
        List<Object[]> batch = new ArrayList<>();
        for (FleetVehicle fv : fleet) {
            FaultPlan p = FaultPlan.forVehicle(fv.vin(), Catalog.byId(fv.modelId()).powertrain(), anchor);
            if (p == null || p.failure().isBefore(start) || !p.failure().isBefore(anchor)) continue;
            FaultPlan.Component c = p.component();
            batch.add(new Object[]{fv.tenantId(), fv.id(), Timestamp.from(p.failure()), "BREAKDOWN", c.label,
                    c.finalDtc, c.repairCostUsd * 1.6});   // breakdowns add towing and downtime
            batch.add(new Object[]{fv.tenantId(), fv.id(), Timestamp.from(p.failure().plus(Duration.ofDays(1))),
                    "REPAIR", c.label, c.finalDtc, c.repairCostUsd});
        }
        postgres.batchUpdate("""
                INSERT INTO maintenance_event (tenant_id, vehicle_id, occurred_at, kind, component, dtc, cost_usd)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?)""", batch);
        log.info("backfill: {} maintenance events written", batch.size());
    }
}
