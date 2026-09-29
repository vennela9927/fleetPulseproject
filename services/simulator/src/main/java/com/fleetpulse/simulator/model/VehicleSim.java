package com.fleetpulse.simulator.model;

import com.fleetpulse.common.event.EventType;
import com.fleetpulse.simulator.fleet.Catalog.Model;
import com.fleetpulse.simulator.fleet.Catalog.Powertrain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * One simulated vehicle: a small state machine (parked, driving, idling, charging) plus
 * physical signals that evolve with it. {@link #step} advances the state by {@code dt}
 * seconds and returns a {@link Sample} of what the vehicle would report.
 *
 * <p>Not thread-safe; each vehicle is only ever stepped by one thread at a time.
 */
public final class VehicleSim {

    public enum Mode { PARKED, DRIVING, IDLING, CHARGING }

    /** What the vehicle reports at one instant, in canonical units. */
    public record Sample(String vin, String oem, long seq, Instant ts, double lat, double lon,
                         double speedKmh, double odoKm, boolean engineOn, int rpm,
                         Double fuelPct, Double socPct, Double sohPct, Double coolantC,
                         double battV, List<String> dtc, EventType evt) {}

    private static final double KM_PER_DEG_LAT = 111.32;
    private static final double OPERATING_RADIUS_KM = 12;

    public final String vin;
    public final Model model;
    public final double homeLat, homeLon;
    private final boolean aggressive;
    private final boolean noisySensors;
    private final double baseSoh;
    private final SplittableRandom rnd;

    private FaultPlan plan;          // scheduled ground-truth fault (may be null)
    private volatile FaultPlan injected;   // demo fault injected at runtime (compressed timeline)
    private volatile Instant coolantSensorBrokenUntil = Instant.EPOCH;   // demo: the sensor, not the engine, fails

    private Mode mode = Mode.PARKED;
    private Instant modeUntil;
    private double lat, lon, targetLat, targetLon;
    private double speed, cruise, odo, fuel = 70, soc = 80, coolant = 30, battV = 12.6;
    private boolean ignitionChanged;
    private long seq;

    public VehicleSim(String vin, Model model, double homeLat, double homeLon, Instant start, long firstSeq) {
        this.vin = vin;
        this.model = model;
        this.homeLat = homeLat;
        this.homeLon = homeLon;
        this.rnd = new SplittableRandom(FaultPlan.seed(vin));
        this.aggressive = rnd.nextDouble() < 0.10;
        this.noisySensors = rnd.nextDouble() < 0.20;
        this.baseSoh = 88 + rnd.nextDouble() * 12;
        this.odo = 5_000 + rnd.nextDouble() * 80_000;
        this.fuel = 30 + rnd.nextDouble() * 70;
        this.soc = 30 + rnd.nextDouble() * 70;
        this.seq = firstSeq;
        double[] p = randomPointNear(homeLat, homeLon, OPERATING_RADIUS_KM);
        this.lat = p[0];
        this.lon = p[1];
        // Stagger vehicles so they do not all change mode at the same instant.
        this.mode = rnd.nextDouble() < 0.7 ? Mode.DRIVING : Mode.PARKED;
        this.modeUntil = start.plusSeconds(rnd.nextInt(1800));
        pickTarget();
    }

    public void schedule(FaultPlan plan) {
        this.plan = plan;
    }

    public FaultPlan plan() {
        return plan;
    }

    /** Demo hook: starts a fault that goes from onset to breakdown in {@code over}. */
    public void inject(FaultPlan.Component c, Instant now, Duration over) {
        this.injected = new FaultPlan(c, now, now.plus(over), 1.0);
    }

    /**
     * Demo hook: the coolant sensor fails (a loose connector) until {@code until}. The engine is
     * fine; the sensor reports physically impossible jumps between cold and boiling.
     */
    public void breakCoolantSensor(Instant until) {
        this.coolantSensorBrokenUntil = until;
    }

    /** Ground truth for a workshop inspection: the component that is actually failing now, if any. */
    public FaultPlan.Component faultAt(Instant now) {
        FaultPlan active = injected != null && injected.severity(now) > 0 ? injected : plan;
        return active != null && active.severity(now) > 0 ? active.component() : null;
    }

    public Mode mode() {
        return mode;
    }

    public Sample step(Instant now, double dt) {
        ignitionChanged = false;
        if (!now.isBefore(modeUntil)) transition(now);

        FaultPlan active = injected != null && injected.severity(now) > 0 ? injected : plan;
        double s = active == null ? 0 : active.severity(now);
        FaultPlan.Component fc = s > 0 ? active.component() : null;

        EventType evt = ignitionChanged ? (engineOn() ? EventType.IGNITION_ON : EventType.IGNITION_OFF) : EventType.PERIODIC;
        switch (mode) {
            case DRIVING -> evt = drive(dt, fc, s, evt);
            case IDLING -> speed = 0;
            case PARKED -> speed = 0;
            case CHARGING -> {
                speed = 0;
                soc = Math.min(100, soc + 0.6 * dt / 60);
                if (soc >= 95) modeUntil = now;
            }
        }
        updateThermalsAndElectrics(dt, fc, s, active == injected);
        List<String> dtc = faultCodes(dt, fc, s);
        seq++;

        boolean ev = model.powertrain() == Powertrain.EV;
        boolean ice = model.powertrain() != Powertrain.EV;
        int rpm = !engineOn() || ev ? 0 : (int) (speed < 1 ? 780 + rnd.nextInt(60) : 1100 + speed * 24 + rnd.nextInt(120));
        if (fc == FaultPlan.Component.IGNITION && rpm > 0) rpm += (int) ((rnd.nextDouble() - 0.5) * 600 * s);
        if (fc == FaultPlan.Component.TRANSMISSION && speed > 20) rpm += (int) (400 * s);
        double soh = baseSoh - (fc == FaultPlan.Component.EV_BATTERY ? 9 * s : 0);
        double coolantReported = now.isBefore(coolantSensorBrokenUntil)
                ? (seq % 2 == 0 ? 20 : 135) + rnd.nextDouble() * 15   // flips between cold and boiling
                : coolant;

        return new Sample(vin, model.oem(), seq, now, lat, lon, round1(speed), round1(odo), engineOn(), rpm,
                ice ? round1(fuel) : null,
                model.powertrain() != Powertrain.ICE ? round1(soc) : null,
                model.powertrain() != Powertrain.ICE ? round1(soh) : null,
                ice ? round1(coolantReported) : null,
                Math.round(battV * 100) / 100.0, dtc, evt);
    }

    public boolean engineOn() {
        return mode == Mode.DRIVING || mode == Mode.IDLING;
    }

    private void transition(Instant now) {
        boolean wasOn = engineOn();
        boolean ev = model.powertrain() == Powertrain.EV;
        switch (mode) {
            case DRIVING -> {
                double r = rnd.nextDouble();
                // Aggressive drivers and delivery vans idle more; this drives the idling alert.
                mode = r < (aggressive ? 0.35 : 0.15) && !ev ? Mode.IDLING : Mode.PARKED;
                modeUntil = now.plusSeconds(mode == Mode.IDLING ? 120 + rnd.nextInt(900) : 300 + rnd.nextInt(1800));
                if (ev && soc < 35) {
                    mode = Mode.CHARGING;
                    modeUntil = now.plusSeconds(4 * 3600);
                }
                if (!ev && fuel < 15) fuel = 95;   // refuelled while parked
            }
            default -> {
                mode = Mode.DRIVING;
                modeUntil = now.plusSeconds(600 + rnd.nextInt(2400));
                cruise = 25 + rnd.nextDouble() * 45;
                pickTarget();
            }
        }
        ignitionChanged = wasOn != engineOn();
    }

    private EventType drive(double dt, FaultPlan.Component fc, double s, EventType evt) {
        // Accelerate towards cruise speed with some noise, then move towards the target.
        double desired = Math.max(0, cruise + (rnd.nextDouble() - 0.5) * 12);
        double harshRate = (aggressive ? 12 : 1.5) * (fc == FaultPlan.Component.BRAKES ? 1 + 4 * s : 1);  // per hour
        if (rnd.nextDouble() < harshRate * dt / 3600) {
            boolean brake = rnd.nextBoolean();
            desired = brake ? Math.max(0, speed - 30) : speed + 25;
            evt = brake ? EventType.HARSH_BRAKE : EventType.HARSH_ACCEL;
        }
        speed = Math.min(120, speed + (desired - speed) * Math.min(1, dt / 8));
        double km = speed * dt / 3600;
        double dLat = targetLat - lat, dLon = (targetLon - lon) * Math.cos(Math.toRadians(lat));
        double distKm = Math.hypot(dLat, dLon) * KM_PER_DEG_LAT;
        if (distKm < Math.max(0.2, km)) {
            pickTarget();
        } else {
            // Move km along the unit vector to the target (degrees scale linearly with km).
            lat += dLat * km / distKm;
            lon += (targetLon - lon) * km / distKm;
        }
        odo += km;
        if (model.powertrain() == Powertrain.EV) soc = Math.max(0, soc - km / model.rangeKm() * 100);
        else fuel = Math.max(0, fuel - km / model.rangeKm() * 100);
        if (model.powertrain() == Powertrain.HYBRID) soc = 40 + 30 * Math.sin(odo / 7);
        return evt;
    }

    private void updateThermalsAndElectrics(double dt, FaultPlan.Component fc, double s, boolean demoFault) {
        double target = engineOn() ? 90 + (speed > 60 ? 4 : 0) : 32;
        double settleSeconds = 300;
        if (fc == FaultPlan.Component.COOLING && engineOn()) {
            target += 14 * s + (rnd.nextDouble() < 0.1 * s ? 20 * s : 0);
            // A demo fault runs on a compressed timeline all the way to failure: in its second half
            // the cooling system gives out and the engine overheats quickly, as with a failed water
            // pump. Natural faults keep only the slow drift, so live data matches the history the
            // failure model is trained on.
            if (demoFault && s > 0.5) {
                target = Math.min(125, target + 60 * (s - 0.5) / 0.5);
                settleSeconds = 30;
            }
        }
        coolant += (target - coolant) * Math.min(1, dt / settleSeconds);
        if (noisySensors) coolant += (rnd.nextDouble() - 0.5) * 4;

        double vTarget = engineOn() ? 14.1 : 12.6;
        if (fc == FaultPlan.Component.ELECTRICAL) vTarget -= 1.4 * s;
        battV += (vTarget - battV) * Math.min(1, dt / 120) + (rnd.nextDouble() - 0.5) * 0.04;
    }

    private List<String> faultCodes(double dt, FaultPlan.Component fc, double s) {
        List<String> codes = new ArrayList<>(2);
        double hours = dt / 3600;
        if (fc != null) {
            switch (fc) {
                case COOLING -> { maybe(codes, "P0128", 2 * s, hours); maybe(codes, "P0217", 4 * s * s * s, hours); }
                case IGNITION -> { maybe(codes, "P0301", 4 * s, hours); maybe(codes, "P0300", 2 * s * s, hours); }
                case ELECTRICAL -> maybe(codes, "P0562", 3 * s * s, hours);
                case EV_BATTERY -> { maybe(codes, "P0AFA", 2 * s, hours); maybe(codes, s > 0.6 ? "P0AA6" : "P0A80", 1.5 * s * s * s, hours); }
                case TRANSMISSION -> { maybe(codes, "P0741", 2 * s, hours); maybe(codes, "P0700", 1.5 * s * s, hours); }
                case BRAKES -> { maybe(codes, "C0035", 2 * s, hours); maybe(codes, "C0265", s * s, hours); }
            }
        }
        // Benign background codes on some vehicles, so "any fault code" is not a perfect predictor.
        if (noisySensors && model.powertrain() != Powertrain.EV) maybe(codes, rnd.nextBoolean() ? "P0420" : "P0171", 0.08, hours);
        if (rnd.nextDouble() < 0.002 * hours) codes.add("U0100");
        return codes;
    }

    private void maybe(List<String> codes, String code, double perHour, double hours) {
        if (engineOn() && rnd.nextDouble() < perHour * hours) codes.add(code);
    }

    private void pickTarget() {
        double[] p = randomPointNear(homeLat, homeLon, OPERATING_RADIUS_KM);
        targetLat = p[0];
        targetLon = p[1];
    }

    private double[] randomPointNear(double lat0, double lon0, double radiusKm) {
        double r = radiusKm * Math.sqrt(rnd.nextDouble());
        double theta = rnd.nextDouble() * 2 * Math.PI;
        double dLat = r * Math.cos(theta) / KM_PER_DEG_LAT;
        double dLon = r * Math.sin(theta) / (KM_PER_DEG_LAT * Math.cos(Math.toRadians(lat0)));
        return new double[]{lat0 + dLat, lon0 + dLon};
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
