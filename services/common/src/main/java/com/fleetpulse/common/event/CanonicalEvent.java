package com.fleetpulse.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The one telemetry format every OEM payload is normalised into (schema version 1).
 * Units are fixed: km/h, km, degrees Celsius, volts, percentages 0-100, UTC instants.
 * Fields that only apply to some powertrains (fuel, SoC, coolant) are nullable.
 *
 * <p>{@code (vin, seq)} identifies an event: the OEM's per-vehicle sequence number is
 * the idempotency key used for de-duplication downstream.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalEvent(
        int schemaVersion,
        String vin,
        String oem,
        long seq,
        Instant ts,
        Instant ingestTs,
        double lat,
        double lon,
        double speedKmh,
        double odoKm,
        boolean engineOn,
        int rpm,
        Double fuelPct,
        Double socPct,
        Double sohPct,
        Double coolantC,
        Double battV,
        List<String> dtc,
        EventType evt) {

    public static final int SCHEMA_VERSION = 1;

    public CanonicalEvent {
        dtc = dtc == null ? List.of() : List.copyOf(dtc);
        if (evt == null) evt = EventType.PERIODIC;
    }

    public CanonicalEvent withIngestTs(Instant at) {
        return new CanonicalEvent(schemaVersion, vin, oem, seq, ts, at, lat, lon, speedKmh, odoKm,
                engineOn, rpm, fuelPct, socPct, sohPct, coolantC, battV, dtc, evt);
    }
}
