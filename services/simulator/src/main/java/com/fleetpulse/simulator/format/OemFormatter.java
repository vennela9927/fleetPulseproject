package com.fleetpulse.simulator.format;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetpulse.common.dtc.Dtc;
import com.fleetpulse.common.event.EventType;
import com.fleetpulse.simulator.model.VehicleSim.Sample;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Renders a sample in each OEM's native wire format. The four formats differ the way
 * real OEM feeds do: field names, nesting, units (metric vs imperial), timestamp style
 * (ISO, epoch ms, epoch s, local "yyyy-MM-dd HH:mm:ss"), and how fault codes are sent
 * (text array, CSV string, raw hex, array of objects).
 */
public final class OemFormatter {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final DateTimeFormatter DRACO_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Map<EventType, String> BOREALIS_EVT = Map.of(
            EventType.HARSH_BRAKE, "HB", EventType.HARSH_ACCEL, "HA",
            EventType.IGNITION_ON, "IG1", EventType.IGNITION_OFF, "IG0");
    private static final Map<EventType, String> CYGNUS_EVT = Map.of(
            EventType.HARSH_BRAKE, "harsh_braking", EventType.HARSH_ACCEL, "harsh_acceleration",
            EventType.CHARGING_START, "charge_start", EventType.CHARGING_STOP, "charge_stop",
            EventType.IGNITION_ON, "power_on", EventType.IGNITION_OFF, "power_off");
    private static final Map<EventType, String> DRACO_EVT = Map.of(
            EventType.HARSH_BRAKE, "BRAKE_HARD", EventType.HARSH_ACCEL, "ACCEL_HARD",
            EventType.IGNITION_ON, "KEY_ON", EventType.IGNITION_OFF, "KEY_OFF");

    private OemFormatter() {}

    public static ObjectNode format(Sample s) {
        return switch (s.oem()) {
            case "AURORA" -> aurora(s);
            case "BOREALIS" -> borealis(s);
            case "CYGNUS" -> cygnus(s);
            case "DRACO" -> draco(s);
            default -> throw new IllegalArgumentException("unknown OEM " + s.oem());
        };
    }

    static ObjectNode aurora(Sample s) {
        ObjectNode o = F.objectNode();
        o.put("vin", s.vin()).put("seq", s.seq()).put("timestamp", s.ts().toString());
        o.putObject("location").put("lat", s.lat()).put("lng", s.lon());
        o.putObject("speed").put("value", s.speedKmh()).put("unit", "kph");
        o.put("odometer_km", s.odoKm());
        ObjectNode engine = o.putObject("engine").put("on", s.engineOn()).put("rpm", s.rpm());
        if (s.coolantC() != null) engine.put("coolant_c", s.coolantC());
        if (s.fuelPct() != null) o.put("fuel_pct", s.fuelPct());
        if (s.socPct() != null) o.putObject("ev").put("soc_pct", s.socPct()).put("soh_pct", s.sohPct());
        o.put("battery_12v", s.battV());
        ArrayNode d = o.putArray("dtcs");
        s.dtc().forEach(d::add);
        o.put("event", s.evt().name());
        return o;
    }

    static ObjectNode borealis(Sample s) {
        ObjectNode o = F.objectNode();
        o.putObject("vehicle").put("id", s.vin());
        o.put("msg_no", s.seq()).put("t", s.ts().toEpochMilli());
        o.putArray("gps").add(s.lat()).add(s.lon());
        o.put("spd_mph", r2(s.speedKmh() / 1.609344)).put("odo_mi", r2(s.odoKm() / 1.609344));
        o.put("ign", s.engineOn() ? 1 : 0).put("rpm", s.rpm());
        if (s.coolantC() != null) o.put("ect_f", r2(s.coolantC() * 9 / 5 + 32));
        if (s.fuelPct() != null) o.put("fuel_frac", r2(s.fuelPct() / 100));
        if (s.socPct() != null) o.put("hv_soc", r2(s.socPct() / 100));
        o.put("v12", s.battV());
        o.put("faults", String.join(",", s.dtc()));
        o.put("ev_code", BOREALIS_EVT.getOrDefault(s.evt(), "PER"));
        return o;
    }

    static ObjectNode cygnus(Sample s) {
        ObjectNode o = F.objectNode();
        o.put("deviceVin", s.vin()).put("counter", s.seq()).put("capturedAt", s.ts().toEpochMilli() / 1000.0);
        ArrayNode sig = o.putArray("signals");
        signal(sig, "LAT", s.lat());
        signal(sig, "LON", s.lon());
        signal(sig, "SPEED_KPH", s.speedKmh());
        signal(sig, "ODO_KM", s.odoKm());
        if (s.socPct() != null) signal(sig, "SOC", s.socPct());
        if (s.sohPct() != null) signal(sig, "SOH", s.sohPct());
        signal(sig, "LV_BATT_V", s.battV());
        sig.addObject().put("name", "POWER_STATE").put("value", s.engineOn() ? "DRIVE" : "PARK");
        ArrayNode codes = o.putObject("diag").putArray("codes");
        s.dtc().forEach(c -> codes.add(String.format("%04X", Dtc.toRaw(c))));   // raw bus format
        o.put("eventType", CYGNUS_EVT.getOrDefault(s.evt(), "periodic"));
        return o;
    }

    static ObjectNode draco(Sample s) {
        ObjectNode o = F.objectNode();
        ObjectNode truck = o.putObject("truck").put("vin_no", s.vin());
        ObjectNode t = truck.putObject("telemetry");
        t.putObject("pos").put("latitude", s.lat()).put("longitude", s.lon());
        t.put("velocity_kmh", s.speedKmh());
        t.putObject("odometer").put("km", s.odoKm());
        t.put("engine_state", s.engineOn() ? "RUNNING" : "STOPPED").put("engine_rpm", s.rpm());
        if (s.coolantC() != null) t.putObject("coolant_temp").put("celsius", s.coolantC());
        if (s.fuelPct() != null) t.put("fuel_level_percent", s.fuelPct());
        t.put("battery_voltage", s.battV());
        ArrayNode alarms = truck.putArray("alarms");
        s.dtc().forEach(c -> alarms.addObject().put("code", c).put("level", c.startsWith("P02") ? "HIGH" : "MEDIUM"));
        o.put("sequence", s.seq()).put("sent_at", DRACO_TS.format(s.ts()));
        o.put("event_kind", DRACO_EVT.getOrDefault(s.evt(), "HEARTBEAT"));
        return o;
    }

    private static void signal(ArrayNode arr, String name, double value) {
        arr.addObject().put("name", name).put("value", value);
    }

    private static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
