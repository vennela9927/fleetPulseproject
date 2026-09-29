package com.fleetpulse.common.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.EventType;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.vin.Vin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EventMapperTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:15:30Z");
    static final String AUR_VIN = Vin.generate("AUR", "MT4C2", 2024, 'B', 7);
    static final String BRL_VIN = Vin.generate("BRL", "HL2D4", 2023, 'A', 7);
    static final String CYG_VIN = Vin.generate("CYG", "EV7A1", 2026, 'C', 7);
    static final String DRC_VIN = Vin.generate("DRC", "TT9K3", 2025, 'D', 7);

    static EventMapper load(String name) throws Exception {
        try (InputStream in = EventMapperTest.class.getResourceAsStream("/mappings/" + name + ".json")) {
            return EventMapper.compile(Json.MAPPER.readValue(in, MappingSpec.class));
        }
    }

    static JsonNode json(String s) throws Exception {
        return Json.MAPPER.readTree(s);
    }

    @Test
    void mapsAuroraMetricPayload() throws Exception {
        CanonicalEvent e = load("aurora").map(json("""
                {"vin":"%s","seq":88412,"timestamp":"2026-09-29T10:15:02.120Z",
                 "location":{"lat":12.9716,"lng":77.5946},"speed":{"value":"64.2","unit":"kph"},
                 "odometer_km":18234.7,"engine":{"on":true,"rpm":2100,"coolant_c":92.5},
                 "fuel_pct":41,"battery_12v":12.6,"dtcs":["p0301","P0301","0420"],"event":"HARSH_BRAKE"}
                """.formatted(AUR_VIN)), NOW);

        assertThat(e.vin()).isEqualTo(AUR_VIN);
        assertThat(e.oem()).isEqualTo("AURORA");
        assertThat(e.seq()).isEqualTo(88412);
        assertThat(e.ts()).isEqualTo(Instant.parse("2026-09-29T10:15:02.120Z"));
        assertThat(e.speedKmh()).isEqualTo(64.2);
        assertThat(e.engineOn()).isTrue();
        assertThat(e.coolantC()).isEqualTo(92.5);
        assertThat(e.socPct()).isNull();
        assertThat(e.dtc()).containsExactly("P0301", "P0420");   // de-duplicated, hex decoded
        assertThat(e.evt()).isEqualTo(EventType.HARSH_BRAKE);
    }

    @Test
    void mapsBorealisImperialPayload() throws Exception {
        CanonicalEvent e = load("borealis").map(json("""
                {"vehicle":{"id":"%s"},"msg_no":12,"t":1790676900000,"gps":[19.076,72.8777],
                 "spd_mph":40,"odo_mi":1000,"ign":1,"rpm":1800,"ect_f":212,"fuel_frac":0.5,
                 "v12":12.4,"faults":"P0217, P0128","ev_code":"HA"}
                """.formatted(BRL_VIN)), NOW);

        assertThat(e.speedKmh()).isCloseTo(64.4, within(0.1));
        assertThat(e.odoKm()).isCloseTo(1609.3, within(0.1));
        assertThat(e.coolantC()).isEqualTo(100.0);
        assertThat(e.fuelPct()).isEqualTo(50.0);
        assertThat(e.engineOn()).isTrue();
        assertThat(e.dtc()).containsExactly("P0217", "P0128");
        assertThat(e.evt()).isEqualTo(EventType.HARSH_ACCEL);
        assertThat(e.ts()).isEqualTo(Instant.ofEpochMilli(1790676900000L));
    }

    @Test
    void mapsCygnusSignalArrayPayload() throws Exception {
        CanonicalEvent e = load("cygnus").map(json("""
                {"deviceVin":"%s","counter":5,"capturedAt":1790676900.5,
                 "signals":[{"name":"LAT","value":13.08},{"name":"LON","value":80.27},
                            {"name":"SPEED_KPH","value":0},{"name":"ODO_KM","value":5000},
                            {"name":"SOC","value":8.5},{"name":"SOH","value":91.2},
                            {"name":"LV_BATT_V","value":12.1},{"name":"POWER_STATE","value":"PARK"}],
                 "diag":{"codes":["0A80","0AA6"]},"eventType":"charge_start"}
                """.formatted(CYG_VIN)), NOW);

        assertThat(e.lat()).isEqualTo(13.08);
        assertThat(e.socPct()).isEqualTo(8.5);
        assertThat(e.sohPct()).isEqualTo(91.2);
        assertThat(e.engineOn()).isFalse();
        assertThat(e.rpm()).isZero();
        assertThat(e.fuelPct()).isNull();
        assertThat(e.dtc()).containsExactly("P0A80", "P0AA6");
        assertThat(e.evt()).isEqualTo(EventType.CHARGING_START);
        assertThat(e.ts()).isEqualTo(Instant.ofEpochMilli(1790676900500L));
    }

    @Test
    void newOemCanBeOnboardedWithASpecAlone() throws Exception {
        // Draco: deeply nested, non-ISO timestamps and alarm objects. No code changes needed.
        MappingSpec spec = new MappingSpec("DRACO", 1, "DRC", Map.ofEntries(
                Map.entry("vin", MappingSpec.FieldRule.of("truck.vin_no")),
                Map.entry("seq", MappingSpec.FieldRule.of("sequence")),
                Map.entry("ts", new MappingSpec.FieldRule("sent_at", "datetime", null, "yyyy-MM-dd HH:mm:ss", null)),
                Map.entry("lat", MappingSpec.FieldRule.of("truck.telemetry.pos.latitude")),
                Map.entry("lon", MappingSpec.FieldRule.of("truck.telemetry.pos.longitude")),
                Map.entry("speedKmh", MappingSpec.FieldRule.of("truck.telemetry.velocity_kmh")),
                Map.entry("engineOn", MappingSpec.FieldRule.of("truck.telemetry.engine_state", "bool")),
                Map.entry("coolantC", MappingSpec.FieldRule.of("truck.telemetry.coolant_temp.celsius")),
                Map.entry("dtc", MappingSpec.FieldRule.of("truck.alarms[*].code", "list")),
                Map.entry("evt", new MappingSpec.FieldRule("event_kind", "map",
                        Map.of("BRAKE_HARD", "HARSH_BRAKE"), null, null))));

        CanonicalEvent e = EventMapper.compile(spec).map(json("""
                {"truck":{"vin_no":"%s","telemetry":{"pos":{"latitude":28.61,"longitude":77.2},
                  "velocity_kmh":55,"engine_state":"RUNNING","coolant_temp":{"celsius":118}},
                  "alarms":[{"code":"P0217","level":"HIGH"},{"code":"P0128"}]},
                 "sequence":3,"sent_at":"2026-09-29 10:15:02","event_kind":"BRAKE_HARD"}
                """.formatted(DRC_VIN)), NOW);

        assertThat(e.oem()).isEqualTo("DRACO");
        assertThat(e.ts()).isEqualTo(Instant.parse("2026-09-29T10:15:02Z"));
        assertThat(e.engineOn()).isTrue();
        assertThat(e.dtc()).containsExactly("P0217", "P0128");
        assertThat(e.evt()).isEqualTo(EventType.HARSH_BRAKE);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', textBlock = """
            {"seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77}}                   | FIELD_MISSING
            {"vin":"AURMT4C2XRB000007","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77}} | VIN_CHECK_DIGIT
            {"vin":"@BRL","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77}}      | VIN_WMI_MISMATCH
            {"vin":"@AUR","seq":1,"location":{"lat":12,"lng":77}}                                          | FIELD_MISSING
            {"vin":"@AUR","seq":1,"timestamp":"yesterday","location":{"lat":12,"lng":77}}                  | FIELD_TYPE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T11:15:00Z","location":{"lat":12,"lng":77}}       | TS_IN_FUTURE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-01T10:15:00Z","location":{"lat":12,"lng":77}}       | TS_TOO_OLD
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":0,"lng":0}}         | GEO_NULL_ISLAND
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":95,"lng":77}}       | GEO_OUT_OF_RANGE
            {"vin":"@AUR","seq":-1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77}}      | SEQ_NEGATIVE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77},"speed":{"value":900}} | SPEED_OUT_OF_RANGE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77},"speed":{"value":"fast"}} | FIELD_TYPE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77},"fuel_pct":140} | PCT_OUT_OF_RANGE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77},"battery_12v":99} | BATT_V_OUT_OF_RANGE
            {"vin":"@AUR","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77},"dtcs":["ENGINE"]} | DTC_INVALID
            """)
    void rejectsBadPayloadsWithStableReasons(String payload, String reason) throws Exception {
        String p = payload.replace("@AUR", AUR_VIN).replace("@BRL", BRL_VIN);
        EventMapper aurora = load("aurora");
        assertThatThrownBy(() -> aurora.map(json(p), NOW))
                .isInstanceOf(MappingException.class)
                .satisfies(ex -> assertThat(((MappingException) ex).reason()).isEqualTo(reason));
    }

    @Test
    void rejectsBrokenSpecsAtCompileTime() {
        assertThatThrownBy(() -> EventMapper.compile(new MappingSpec("X", 1, null,
                Map.of("vin", MappingSpec.FieldRule.of("v")))))
                .hasMessageContaining("missing required field");
        assertThatThrownBy(() -> EventMapper.compile(new MappingSpec("X", 1, null,
                Map.of("colour", MappingSpec.FieldRule.of("c")))))
                .hasMessageContaining("unknown canonical field");
        assertThatThrownBy(() -> EventMapper.compile(new MappingSpec("X", 1, null,
                Map.of("vin", MappingSpec.FieldRule.of("v", "teleport")))))
                .hasMessageContaining("unknown transform");
        assertThatThrownBy(() -> EventMapper.compile(new MappingSpec("X", 1, null,
                Map.of("ts", MappingSpec.FieldRule.of("t", "datetime")))))
                .hasMessageContaining("without a format");
        assertThatThrownBy(() -> EventMapper.compile(new MappingSpec("X", 1, null,
                Map.of("vin", new MappingSpec.FieldRule(null, null, null, null, null)))))
                .hasMessageContaining("needs a path or a constant");
        assertThatThrownBy(() -> EventMapper.compile(new MappingSpec("", 1, null, Map.of())))
                .hasMessageContaining("no OEM");
    }

    @Test
    void canonicalEventRoundTripsThroughJson() throws Exception {
        CanonicalEvent e = load("aurora").map(json("""
                {"vin":"%s","seq":1,"timestamp":"2026-09-29T10:15:00Z","location":{"lat":12,"lng":77}}
                """.formatted(AUR_VIN)), NOW).withIngestTs(NOW);
        CanonicalEvent back = Json.read(Json.write(e), CanonicalEvent.class);
        assertThat(back).isEqualTo(e);
        assertThat(back.evt()).isEqualTo(EventType.PERIODIC);
    }
}
