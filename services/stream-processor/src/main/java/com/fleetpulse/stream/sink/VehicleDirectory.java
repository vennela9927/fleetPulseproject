package com.fleetpulse.stream.sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VIN → vehicle facts the telemetry stream does not carry (owner tenant, fleet, powertrain,
 * database id), held in memory: 100K vehicles ≈ 20 MB, loaded in about a second.
 *
 * <p>New registrations (such as a freshly onboarded OEM's trucks) are picked up by an
 * incremental reload of ids above the highest one seen, triggered on the first unknown VIN
 * and rate-limited so a stream of unregistered VINs cannot hammer Postgres.
 */
public final class VehicleDirectory {

    public record Vehicle(long id, String vin, String tenantId, long fleetId, String powertrain) {}

    private static final Logger log = LoggerFactory.getLogger(VehicleDirectory.class);
    private static final Duration MIN_RELOAD_INTERVAL = Duration.ofSeconds(5);

    private final JdbcTemplate db;
    private final Clock clock;
    private final Map<String, Vehicle> byVin = new ConcurrentHashMap<>(150_000);
    private volatile long maxId;
    private volatile long lastReloadMs;

    public VehicleDirectory(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Loads every vehicle registered since the last load (all of them on the first call). */
    public synchronized int reload() {
        long[] max = {maxId};
        int[] added = {0};
        db.query("""
                SELECT v.id, v.vin, v.tenant_id::text, v.fleet_id, m.powertrain
                FROM vehicle v JOIN vehicle_model m ON m.id = v.model_id
                WHERE v.id > ? ORDER BY v.id""", rs -> {
            Vehicle v = new Vehicle(rs.getLong(1), rs.getString(2).trim(), rs.getString(3), rs.getLong(4), rs.getString(5));
            byVin.put(v.vin(), v);
            max[0] = Math.max(max[0], v.id());
            added[0]++;
        }, maxId);
        maxId = max[0];
        lastReloadMs = clock.millis();
        if (added[0] > 0) log.info("vehicle directory: +{} vehicles, {} total", added[0], byVin.size());
        return added[0];
    }

    /** The vehicle, or null if it is not registered even after a (rate-limited) reload. */
    public Vehicle get(String vin) {
        Vehicle v = byVin.get(vin);
        if (v != null) return v;
        if (clock.millis() - lastReloadMs >= MIN_RELOAD_INTERVAL.toMillis()) {
            reload();
            return byVin.get(vin);
        }
        return null;
    }

    public int size() {
        return byVin.size();
    }

    /** A directory with a fixed set of vehicles that never touches a database (for tests). */
    static VehicleDirectory fixed(Vehicle... vehicles) {
        VehicleDirectory d = new VehicleDirectory(null, Clock.systemUTC());
        for (Vehicle v : vehicles) d.byVin.put(v.vin(), v);
        d.lastReloadMs = Long.MAX_VALUE;   // never due for a reload
        return d;
    }
}
