package com.fleetpulse.stream.sink;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/** A gzipped JSONEachRow body for {@code fleet.telemetry}, built one row at a time. */
final class TelemetryRows {

    private static final DateTimeFormatter CH_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 << 10);
    private final JsonGenerator gen;
    private int count;

    TelemetryRows() {
        try {
            gen = Json.MAPPER.getFactory().createGenerator(new GZIPOutputStream(bytes, 1 << 15), JsonEncoding.UTF8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        gen.setRootValueSeparator(new SerializedString("\n"));
    }

    void add(CanonicalEvent e, VehicleDirectory.Vehicle v) {
        try {
            gen.writeStartObject();
            gen.writeStringField("vin", e.vin());
            gen.writeStringField("tenant_id", v.tenantId());
            gen.writeNumberField("fleet_id", v.fleetId());
            gen.writeStringField("oem", e.oem());
            gen.writeStringField("powertrain", v.powertrain());
            gen.writeNumberField("seq", e.seq());
            gen.writeStringField("ts", CH_TS.format(e.ts()));
            gen.writeStringField("ingest_ts", CH_TS.format(e.ingestTs() != null ? e.ingestTs() : e.ts()));
            gen.writeNumberField("lat", e.lat());
            gen.writeNumberField("lon", e.lon());
            gen.writeNumberField("speed_kmh", e.speedKmh());
            gen.writeNumberField("odo_km", e.odoKm());
            gen.writeNumberField("engine_on", e.engineOn() ? 1 : 0);
            gen.writeNumberField("rpm", Math.max(0, e.rpm()));
            nullable("fuel_pct", e.fuelPct());
            nullable("soc_pct", e.socPct());
            nullable("soh_pct", e.sohPct());
            nullable("coolant_c", e.coolantC());
            gen.writeNumberField("batt_v", e.battV() != null ? e.battV() : 0);
            gen.writeArrayFieldStart("dtc");
            for (String c : e.dtc()) gen.writeString(c);
            gen.writeEndArray();
            gen.writeStringField("evt", e.evt().name());
            gen.writeEndObject();
            count++;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    int count() {
        return count;
    }

    /** Closes the stream; call once. */
    byte[] finish() {
        try {
            gen.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private void nullable(String name, Double v) throws IOException {
        if (v == null) gen.writeNullField(name);
        else gen.writeNumberField(name, v);
    }
}
