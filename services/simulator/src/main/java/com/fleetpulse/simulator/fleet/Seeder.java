package com.fleetpulse.simulator.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.vin.Vin;
import com.fleetpulse.simulator.SimProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Loads the synthetic fleet into Postgres with COPY (100K vehicles + drivers in seconds,
 * versus minutes with row-by-row INSERTs) and publishes the built-in OEM mappings.
 * Idempotent: skips vehicles if they are already there, and mapping records are keyed
 * by OEM on a compacted topic.
 */
@Component
public class Seeder {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);
    private static final String[] FIRST = {"Aarav", "Vivaan", "Aditya", "Diya", "Ananya", "Ishaan", "Kavya", "Rohan",
            "Priya", "Arjun", "Meera", "Sai", "Lakshmi", "Rahul", "Neha", "Vikram", "Pooja", "Karthik", "Sneha", "Harish"};
    private static final String[] LAST = {"Sharma", "Reddy", "Iyer", "Nair", "Patel", "Singh", "Gupta", "Rao", "Menon",
            "Das", "Kumar", "Joshi", "Mehta", "Pillai", "Verma"};
    static final List<String> BUILT_IN_MAPPINGS = List.of("aurora", "borealis", "cygnus");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final SimProperties props;

    public Seeder(DataSource dataSource, SimProperties props) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.props = props;
    }

    public void seed() throws Exception {
        seedMappings();
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM vehicle", Integer.class);
        if (existing != null && existing >= props.vehicles()) {
            log.info("seed: {} vehicles already present, skipping", existing);
            return;
        }
        if (existing != null && existing > 0)
            throw new IllegalStateException("partial seed found (" + existing + " vehicles); reset the database volume");

        List<Map<String, Object>> fleets = jdbc.queryForList("SELECT id, tenant_id FROM fleet ORDER BY id");
        int n = props.vehicles();
        long t0 = System.currentTimeMillis();
        StringBuilder vehicles = new StringBuilder(n * 80);
        StringBuilder drivers = new StringBuilder(n * 90);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < n; i++) {
            Catalog.Model m = Catalog.modelFor(i, n);
            Map<String, Object> fleet = fleets.get(i % fleets.size());
            int year = 2019 + (i % 8);
            String vin = Vin.generate(m.wmi(), m.vds(), year, (char) ('A' + i % 4), i);
            vehicles.append(vin).append(',').append(fleet.get("id")).append(',').append(fleet.get("tenant_id"))
                    .append(',').append(m.id()).append(',').append(year).append('\n');
            String name = FIRST[i % FIRST.length] + " " + LAST[(i / FIRST.length) % LAST.length];
            String licence = HexFormat.of().formatHex(sha.digest(("DL-" + i + "-salt").getBytes(StandardCharsets.UTF_8)));
            drivers.append(fleet.get("tenant_id")).append(',').append(name).append(',')
                    .append("driver").append(i).append("@example.test").append(',').append(licence).append('\n');
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            var copy = c.unwrap(PGConnection.class).getCopyAPI();
            copy.copyIn("COPY vehicle (vin, fleet_id, tenant_id, model_id, model_year) FROM STDIN (FORMAT csv)",
                    new StringReader(vehicles.toString()));
            copy.copyIn("COPY driver (tenant_id, full_name, email, licence_hash) FROM STDIN (FORMAT csv)",
                    new StringReader(drivers.toString()));
            try (var st = c.createStatement()) {
                st.execute("""
                        INSERT INTO driver_assignment (vehicle_id, driver_id, valid_from)
                        SELECT v.id, d.id, now() - interval '60 days'
                        FROM (SELECT id, row_number() OVER (ORDER BY id) rn FROM vehicle) v
                        JOIN (SELECT id, row_number() OVER (ORDER BY id) rn FROM driver) d USING (rn)""");
                st.execute("ANALYZE vehicle; ANALYZE driver; ANALYZE driver_assignment");
            }
            c.commit();
        }
        log.info("seed: {} vehicles and drivers loaded in {} ms", n, System.currentTimeMillis() - t0);
    }

    /** Stores the built-in specs as ACTIVE v1 and publishes them to the compacted mappings topic. */
    void seedMappings() throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            for (String name : BUILT_IN_MAPPINGS) {
                String spec;
                try (InputStream in = getClass().getResourceAsStream("/mappings/" + name + ".json")) {
                    spec = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                JsonNode node = Json.MAPPER.readTree(spec);
                String oem = node.get("oem").asText();
                jdbc.update("""
                        INSERT INTO oem_mapping (oem_code, version, spec, status, proposed_by)
                        VALUES (?, ?, ?::jsonb, 'ACTIVE', 'seed') ON CONFLICT (oem_code, version) DO NOTHING""",
                        oem, node.get("version").asInt(), spec);
                producer.send(new ProducerRecord<>("oem.mappings", oem, Json.MAPPER.writeValueAsString(node))).get();
            }
        }
        log.info("seed: published built-in mappings {}", BUILT_IN_MAPPINGS);
    }

    /** Vehicles for the live run and backfill, with their home depot and tenant. */
    public record FleetVehicle(long id, String vin, int modelId, long fleetId, String tenantId, double lat, double lon) {}

    /**
     * The first {@code limit} vehicles of an evenly spaced sample, so a smaller run still has
     * every maker (ids are grouped by model) and every tenant, in proportion.
     */
    public List<FleetVehicle> loadFleet(int limit) {
        return jdbc.query("""
                        SELECT id, vin, model_id, fleet_id, tenant_id, lat, lon FROM (
                            SELECT v.id, v.vin, v.model_id, v.fleet_id, v.tenant_id::text AS tenant_id, d.lat, d.lon,
                                   row_number() OVER (ORDER BY v.id) AS rn, count(*) OVER () AS total
                            FROM vehicle v JOIN fleet f ON f.id = v.fleet_id JOIN depot d ON d.id = f.home_depot_id) x
                        WHERE (rn - 1) % greatest(1, total / ?) = 0
                        ORDER BY id LIMIT ?""",
                (rs, i) -> new FleetVehicle(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        rs.getString(5), rs.getDouble(6), rs.getDouble(7)),
                limit, limit);
    }
}
