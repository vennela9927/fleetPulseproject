package com.fleetpulse.stream.alert;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.EventType;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates one vehicle's event against its remembered state. Pure logic: the caller loads
 * and stores the state, so the same code runs in the stream topology and in unit tests.
 *
 * <p>Two kinds of rule:
 * <ul>
 *   <li><b>Point rules</b> (critical fault code, low 12 V, low EV charge) look at one event.</li>
 *   <li><b>Episode rules</b> (overheat, idling) need a condition to hold for a duration. An
 *       episode starts at the first event where the condition holds, ends at the first where
 *       it does not, and fires at most once. A gap in reporting longer than {@link #MAX_GAP}
 *       also ends it, so a vehicle that went silent is not assumed to have idled throughout.</li>
 * </ul>
 * Out-of-order events are expected (about 2% in the simulator). A late event still counts for
 * point rules and harsh-event windows, which are keyed by the event's own time, but it does
 * not move episodes, which only make sense in time order.
 *
 * <p>Every alert's identity comes from vehicle timestamps only, so re-processing the same
 * events after a crash reproduces the same dedup keys.
 */
public final class RuleEngine {

    static final double OVERHEAT_C = 110;
    static final Duration OVERHEAT_FOR = Duration.ofSeconds(30);
    static final double LOW_12V = 11.8;
    static final Duration HARSH_WINDOW = Duration.ofMinutes(10);
    static final double EV_LOW_SOC_PCT = 10;
    static final Duration IDLE_FOR = Duration.ofMinutes(10);
    static final Duration MAX_GAP = Duration.ofMinutes(2);
    /** Engine coolant has thermal mass: it cannot move this far between two readings a few seconds apart. */
    static final double COOLANT_MAX_JUMP_C = 25;
    static final int SENSOR_FAULT_JUMPS = 3;
    static final Duration SENSOR_JUMP_WINDOW = Duration.ofMinutes(5);
    static final Duration SENSOR_SUSPECT_FOR = Duration.ofMinutes(30);

    private final Set<String> criticalDtcs;
    private final Clock clock;

    /** @param criticalDtcs fault codes with severity 5 in the {@code dtc_code} table */
    public RuleEngine(Set<String> criticalDtcs, Clock clock) {
        this.criticalDtcs = Set.copyOf(criticalDtcs);
        this.clock = clock;
    }

    List<Alert> evaluate(VehicleRuleState s, CanonicalEvent e) {
        List<Alert> out = new ArrayList<>(1);
        long ts = e.ts().toEpochMilli();
        boolean inOrder = ts >= s.lastTs;

        criticalDtc(s, e, ts, out);
        lowBattery(s, e, ts, out);
        evLowSoc(s, e, ts, out);
        harshDriving(s, e, ts, out);

        if (inOrder) {
            if (s.lastTs != 0 && ts - s.lastTs > MAX_GAP.toMillis()) {
                s.overheatSince = s.idleSince = 0;
                s.overheatRaised = s.idleRaised = false;
            }
            coolantSensor(s, e, ts, out);
            overheat(s, e, ts, out);
            idling(s, e, ts, out);
            s.lastTs = ts;
        }
        return out;
    }

    private void criticalDtc(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        for (String code : e.dtc()) {
            if (criticalDtcs.contains(code) && fire(s, AlertRule.CRITICAL_DTC, ts)) {
                out.add(alert(AlertRule.CRITICAL_DTC, e, ts, Map.of("dtc", code)));
                return;
            }
        }
    }

    private void lowBattery(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        if (!e.engineOn() && e.battV() != null && e.battV() < LOW_12V && fire(s, AlertRule.LOW_12V_BATTERY, ts)) {
            out.add(alert(AlertRule.LOW_12V_BATTERY, e, ts, Map.of("batt_v", e.battV(), "threshold_v", LOW_12V)));
        }
    }

    /** A vehicle that reports charge but no fuel level is battery-electric. */
    private void evLowSoc(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        boolean ev = e.socPct() != null && e.fuelPct() == null;
        if (ev && e.speedKmh() > 0 && e.socPct() < EV_LOW_SOC_PCT && fire(s, AlertRule.EV_LOW_SOC, ts)) {
            out.add(alert(AlertRule.EV_LOW_SOC, e, ts, Map.of("soc_pct", e.socPct(), "threshold_pct", EV_LOW_SOC_PCT)));
        }
    }

    private void harshDriving(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        if (e.evt() != EventType.HARSH_BRAKE && e.evt() != EventType.HARSH_ACCEL) return;
        s.addHarsh(ts);
        if (s.harshCount < VehicleRuleState.HARSH_SLOTS) return;
        long first = s.harsh[0], last = s.harsh[s.harshCount - 1];
        if (last - first <= HARSH_WINDOW.toMillis() && fire(s, AlertRule.HARSH_DRIVING, last)) {
            out.add(alert(AlertRule.HARSH_DRIVING, e, first, Map.of(
                    "events", s.harshCount, "window_seconds", (last - first) / 1000)));
            s.clearHarsh();   // the next alert needs five new events
        }
    }

    /**
     * Sensor fault, not part fault: coolant that jumps by more than {@value #COOLANT_MAX_JUMP_C} °C
     * between consecutive readings, {@value #SENSOR_FAULT_JUMPS} times within five minutes, is a
     * failing sensor or connector. The vehicle needs the sensor checked, not a workshop slot for an
     * engine that is fine, so overheat alerts from that sensor are held back while it is suspect.
     */
    private void coolantSensor(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        Double c = e.coolantC();
        if (c == null) return;
        boolean comparable = s.lastCoolantTs != 0 && ts - s.lastCoolantTs <= MAX_GAP.toMillis();
        if (comparable && Math.abs(c - s.lastCoolant) > COOLANT_MAX_JUMP_C) {
            if (s.firstJumpTs == 0 || ts - s.firstJumpTs > SENSOR_JUMP_WINDOW.toMillis()) {
                s.firstJumpTs = ts;
                s.jumps = 0;
            }
            s.jumps++;
            if (s.jumps >= SENSOR_FAULT_JUMPS) {
                boolean newlySuspect = ts >= s.sensorSuspectUntil;
                s.sensorSuspectUntil = ts + SENSOR_SUSPECT_FOR.toMillis();
                if (newlySuspect && fire(s, AlertRule.SENSOR_FAULT, ts)) {
                    out.add(alert(AlertRule.SENSOR_FAULT, e, s.firstJumpTs, Map.of(
                            "sensor", "coolant", "jumps", s.jumps,
                            "window_seconds", (ts - s.firstJumpTs) / 1000,
                            "readings_c", List.of(s.lastCoolant, c),
                            "max_plausible_change_c", COOLANT_MAX_JUMP_C,
                            "overheat_alerts_held", true)));
                }
            }
        }
        s.lastCoolant = c;
        s.lastCoolantTs = ts;
    }

    private void overheat(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        if (e.coolantC() == null || e.coolantC() <= OVERHEAT_C || ts < s.sensorSuspectUntil) {
            s.overheatSince = 0;
            s.overheatRaised = false;
            return;
        }
        if (s.overheatSince == 0) s.overheatSince = ts;
        if (!s.overheatRaised && ts - s.overheatSince >= OVERHEAT_FOR.toMillis()) {
            s.overheatRaised = true;
            if (fire(s, AlertRule.ENGINE_OVERHEAT, ts)) {
                out.add(alert(AlertRule.ENGINE_OVERHEAT, e, s.overheatSince, Map.of(
                        "coolant_c", e.coolantC(), "threshold_c", OVERHEAT_C,
                        "above_for_seconds", (ts - s.overheatSince) / 1000)));
            }
        }
    }

    private void idling(VehicleRuleState s, CanonicalEvent e, long ts, List<Alert> out) {
        if (!e.engineOn() || e.speedKmh() >= 1) {
            s.idleSince = 0;
            s.idleRaised = false;
            return;
        }
        if (s.idleSince == 0) s.idleSince = ts;
        if (!s.idleRaised && ts - s.idleSince > IDLE_FOR.toMillis()) {
            s.idleRaised = true;
            if (fire(s, AlertRule.EXCESSIVE_IDLING, ts)) {
                out.add(alert(AlertRule.EXCESSIVE_IDLING, e, s.idleSince, Map.of(
                        "idle_seconds", (ts - s.idleSince) / 1000)));
            }
        }
    }

    /**
     * Applies the cooldown; returns true (and records the firing) if the rule may fire.
     * A late event older than the last firing is inside the cooldown too, since the gap is negative.
     */
    private static boolean fire(VehicleRuleState s, AlertRule rule, long ts) {
        if (s.inCooldown(rule, ts)) return false;
        s.raised(rule, ts);
        return true;
    }

    private Alert alert(AlertRule rule, CanonicalEvent e, long episodeStartMs, Map<String, Object> details) {
        Map<String, Object> d = new LinkedHashMap<>(details);
        d.put("lat", e.lat());
        d.put("lon", e.lon());
        return Alert.of(rule, e.vin(), episodeStartMs, e.ts(), e.ingestTs(), clock.instant(), d);
    }
}
