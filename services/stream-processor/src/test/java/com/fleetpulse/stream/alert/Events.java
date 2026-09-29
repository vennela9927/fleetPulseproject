package com.fleetpulse.stream.alert;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.EventType;

import java.time.Instant;
import java.util.List;

/** Builds canonical events for tests: a healthy ICE vehicle driving, unless changed. */
final class Events {

    static final String VIN = "1HGCM82633A004352";
    static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

    String vin = VIN;
    Instant ts = T0;
    long seq = 1;
    double speed = 50;
    boolean engineOn = true;
    Double fuel = 60.0, soc = null, coolant = 91.0, battV = 14.1;
    List<String> dtc = List.of();
    EventType evt = EventType.PERIODIC;

    static Events at(int secondsAfterT0) {
        Events e = new Events();
        e.ts = T0.plusSeconds(secondsAfterT0);
        e.seq = secondsAfterT0 + 1L;
        return e;
    }

    Events coolant(double c) { coolant = c; return this; }
    Events speed(double s) { speed = s; return this; }
    Events engineOff() { engineOn = false; speed = 0; return this; }
    Events battV(double v) { battV = v; return this; }
    Events dtc(String... codes) { dtc = List.of(codes); return this; }
    Events evt(EventType t) { evt = t; return this; }
    Events ev(double socPct) { fuel = null; coolant = null; soc = socPct; return this; }

    CanonicalEvent build() {
        return new CanonicalEvent(CanonicalEvent.SCHEMA_VERSION, vin, "AURORA", seq, ts, ts.plusMillis(300),
                12.97, 77.59, speed, 1000, engineOn, engineOn ? 1500 : 0, fuel, soc, soc, coolant, battV, dtc, evt);
    }
}
