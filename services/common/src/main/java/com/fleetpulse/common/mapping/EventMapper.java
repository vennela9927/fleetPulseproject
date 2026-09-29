package com.fleetpulse.common.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.dtc.Dtc;
import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.EventType;
import com.fleetpulse.common.vin.Vin;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiled, immutable form of a {@link MappingSpec}. Compilation validates the spec up
 * front (unknown fields, bad paths, missing required rules), so a broken spec is
 * rejected when it is proposed rather than failing on live traffic.
 * Thread-safe: one instance is shared by all stream threads.
 */
public final class EventMapper {

    /** Canonical fields a spec may map. */
    public enum Field {
        VIN, SEQ, TS, LAT, LON, SPEED_KMH, ODO_KM, ENGINE_ON, RPM,
        FUEL_PCT, SOC_PCT, SOH_PCT, COOLANT_C, BATT_V, DTC, EVT;

        static Field fromSpecName(String name) {
            // camelCase in specs (speedKmh) → SPEED_KMH
            String upper = name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
            try {
                return valueOf(upper);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown canonical field '" + name + "'");
            }
        }
    }

    private static final List<Field> REQUIRED = List.of(Field.VIN, Field.SEQ, Field.TS, Field.LAT, Field.LON);
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    static final Duration MAX_AGE = Duration.ofDays(7);

    private record Rule(PathExpr path, Transform transform, Map<String, String> lookup,
                        DateTimeFormatter formatter, String constant) {
        Object value(JsonNode root) {
            if (path == null) return constant;
            JsonNode node = path.eval(root);
            if (node.isMissingNode() || node.isNull()) return null;
            try {
                return transform.apply(node, lookup, formatter);
            } catch (MappingException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new MappingException("FIELD_TYPE", path + ": " + e.getMessage());
            }
        }
    }

    private final MappingSpec spec;
    private final EnumMap<Field, Rule> rules = new EnumMap<>(Field.class);

    private EventMapper(MappingSpec spec) {
        this.spec = spec;
    }

    public static EventMapper compile(MappingSpec spec) {
        if (spec.oem() == null || spec.oem().isBlank()) throw new IllegalArgumentException("spec has no OEM code");
        if (spec.fields() == null) throw new IllegalArgumentException("spec has no fields");
        EventMapper m = new EventMapper(spec);
        spec.fields().forEach((name, r) -> {
            Field f = Field.fromSpecName(name);
            if (r.path() == null && r.constant() == null)
                throw new IllegalArgumentException("field '" + name + "' needs a path or a constant");
            Transform t = Transform.parse(r.transform());
            if (t == Transform.DATETIME && r.format() == null)
                throw new IllegalArgumentException("field '" + name + "' uses datetime without a format");
            m.rules.put(f, new Rule(
                    r.path() == null ? null : PathExpr.compile(r.path()),
                    t, r.map(),
                    r.format() == null ? null : DateTimeFormatter.ofPattern(r.format()),
                    r.constant()));
        });
        for (Field f : REQUIRED)
            if (!m.rules.containsKey(f)) throw new IllegalArgumentException("spec is missing required field " + f);
        return m;
    }

    public MappingSpec spec() {
        return spec;
    }

    /** Extracts only the VIN, used by the gateway to choose the Kafka partition key. */
    public String vinOf(JsonNode payload) {
        Object v = rules.get(Field.VIN).value(payload);
        return v == null ? null : v.toString().trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Maps and validates one payload.
     *
     * @throws MappingException with a stable reason code if the payload is unusable
     */
    public CanonicalEvent map(JsonNode payload, Instant now) {
        String vin = vinOf(payload);
        if (vin == null) throw new MappingException("FIELD_MISSING", "vin");
        Vin.Check vc = Vin.check(vin);
        if (!vc.valid()) throw new MappingException(vc.reason(), vin);
        if (spec.wmi() != null && !vin.startsWith(spec.wmi()))
            throw new MappingException("VIN_WMI_MISMATCH", vin + " is not a " + spec.oem() + " VIN");

        long seq = (long) num(payload, Field.SEQ, true);
        Instant ts = instant(payload);
        double lat = num(payload, Field.LAT, true);
        double lon = num(payload, Field.LON, true);
        double speed = num(payload, Field.SPEED_KMH, false);
        double odo = num(payload, Field.ODO_KM, false);
        boolean engineOn = bool(payload, Field.ENGINE_ON);
        int rpm = (int) Math.round(num(payload, Field.RPM, false));
        Double fuel = optNum(payload, Field.FUEL_PCT);
        Double soc = optNum(payload, Field.SOC_PCT);
        Double soh = optNum(payload, Field.SOH_PCT);
        Double coolant = optNum(payload, Field.COOLANT_C);
        Double battV = optNum(payload, Field.BATT_V);
        List<String> dtc = dtcs(payload);
        EventType evt = eventType(payload);

        if (seq < 0) throw new MappingException("SEQ_NEGATIVE", String.valueOf(seq));
        if (ts.isAfter(now.plus(MAX_FUTURE_SKEW))) throw new MappingException("TS_IN_FUTURE", ts.toString());
        if (ts.isBefore(now.minus(MAX_AGE))) throw new MappingException("TS_TOO_OLD", ts.toString());
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180)
            throw new MappingException("GEO_OUT_OF_RANGE", lat + "," + lon);
        if (lat == 0 && lon == 0) throw new MappingException("GEO_NULL_ISLAND", "0,0 is a GPS fault, not a location");
        if (speed < 0 || speed > 300) throw new MappingException("SPEED_OUT_OF_RANGE", String.valueOf(speed));
        pct("fuelPct", fuel);
        pct("socPct", soc);
        pct("sohPct", soh);
        if (battV != null && (battV < 0 || battV > 32)) throw new MappingException("BATT_V_OUT_OF_RANGE", battV.toString());

        return new CanonicalEvent(CanonicalEvent.SCHEMA_VERSION, vin, spec.oem(), seq, ts, null,
                lat, lon, round(speed, 1), round(odo, 1), engineOn, rpm,
                round(fuel), round(soc), round(soh), round(coolant), round(battV), dtc, evt);
    }

    private double num(JsonNode p, Field f, boolean required) {
        Double v = optNum(p, f);
        if (v == null) {
            if (required) throw new MappingException("FIELD_MISSING", f.name().toLowerCase(Locale.ROOT));
            return 0;
        }
        return v;
    }

    private Double optNum(JsonNode p, Field f) {
        Rule r = rules.get(f);
        if (r == null) return null;
        Object v = r.value(p);
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new MappingException("FIELD_TYPE", f + " is not numeric: '" + v + "'");
        }
    }

    private Instant instant(JsonNode p) {
        Object v = rules.get(Field.TS).value(p);
        if (v == null) throw new MappingException("FIELD_MISSING", "ts");
        if (v instanceof Instant i) return i;
        throw new MappingException("FIELD_TYPE", "ts needs a time transform (iso8601, epoch_ms, epoch_s, datetime)");
    }

    private boolean bool(JsonNode p, Field f) {
        Rule r = rules.get(f);
        if (r == null) return false;
        Object v = r.value(p);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    private List<String> dtcs(JsonNode p) {
        Rule r = rules.get(Field.DTC);
        if (r == null) return List.of();
        Object v = r.value(p);
        if (v == null) return List.of();
        List<?> raw = v instanceof List<?> l ? l : Dtc.extract(v.toString());
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            String code = Dtc.normalise(String.valueOf(o));
            if (code == null) throw new MappingException("DTC_INVALID", String.valueOf(o));
            if (!out.contains(code)) out.add(code);
        }
        return out;
    }

    private EventType eventType(JsonNode p) {
        Rule r = rules.get(Field.EVT);
        if (r == null) return EventType.PERIODIC;
        Object v = r.value(p);
        if (v == null) return EventType.PERIODIC;
        try {
            return EventType.valueOf(v.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EventType.PERIODIC;   // unknown event kinds degrade to a periodic sample
        }
    }

    private static void pct(String name, Double v) {
        if (v != null && (v < 0 || v > 100.0001)) throw new MappingException("PCT_OUT_OF_RANGE", name + "=" + v);
    }

    private static Double round(Double v) {
        return v == null ? null : round(v, 2);
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
