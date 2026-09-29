package com.fleetpulse.simulator.model;

import com.fleetpulse.simulator.fleet.Catalog.Powertrain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Hidden ground truth: which vehicles will break down, when, and how the failure shows
 * in the signals beforehand. Derived only from the VIN and an anchor date, so the
 * historical backfill, the live simulator and the labels written to Postgres agree
 * without sharing state.
 *
 * <p>Each fault degrades over a window before the breakdown. Severity {@code s} goes from
 * 0 at onset to 1 at breakdown, and signal drift and fault-code rates scale with it.
 * A share of faults are "silent" (weak signal), so no model can reach perfect recall.
 */
public record FaultPlan(Component component, Instant onset, Instant failure, double strength) {

    public enum Component {
        COOLING("Cooling system", "P0217", 1800, List.of(Powertrain.ICE, Powertrain.HYBRID)),
        IGNITION("Ignition / misfire", "P0300", 650, List.of(Powertrain.ICE, Powertrain.HYBRID)),
        ELECTRICAL("12 V electrical", "P0562", 350, List.of(Powertrain.ICE, Powertrain.HYBRID, Powertrain.EV)),
        EV_BATTERY("High-voltage battery", "P0A80", 4200, List.of(Powertrain.EV, Powertrain.HYBRID)),
        TRANSMISSION("Transmission", "P0700", 2400, List.of(Powertrain.ICE)),
        BRAKES("ABS / brakes", "C0265", 900, List.of(Powertrain.ICE, Powertrain.HYBRID, Powertrain.EV));

        public final String label;
        public final String finalDtc;
        public final double repairCostUsd;
        final List<Powertrain> applies;

        Component(String label, String finalDtc, double repairCostUsd, List<Powertrain> applies) {
            this.label = label;
            this.finalDtc = finalDtc;
            this.repairCostUsd = repairCostUsd;
            this.applies = applies;
        }

        public boolean appliesTo(Powertrain p) {
            return applies.contains(p);
        }
    }

    /** Share of vehicles with a breakdown somewhere in the simulated window. */
    static final double FAULT_RATE = 0.05;
    static final Duration HISTORY = Duration.ofDays(21);
    static final Duration FUTURE = Duration.ofDays(7);

    /**
     * @param anchor start of the simulation day; breakdowns fall in [anchor-21d, anchor+7d]
     * @return the plan, or null for a healthy vehicle
     */
    public static FaultPlan forVehicle(String vin, Powertrain powertrain, Instant anchor) {
        SplittableRandom r = new SplittableRandom(seed(vin) ^ 0x5DEECE66DL);
        if (r.nextDouble() >= FAULT_RATE) return null;
        List<Component> options = java.util.Arrays.stream(Component.values())
                .filter(c -> c.appliesTo(powertrain)).toList();
        Component c = options.get(r.nextInt(options.size()));
        long windowSeconds = HISTORY.plus(FUTURE).toSeconds();
        Instant failure = anchor.minus(HISTORY).plusSeconds(r.nextLong(windowSeconds));
        Instant onset = failure.minus(Duration.ofHours(72 + r.nextInt(7 * 24)));   // 3-10 days of warning
        double strength = r.nextDouble() < 0.15 ? 0.2 : 0.7 + 0.3 * r.nextDouble();
        return new FaultPlan(c, onset, failure, strength);
    }

    /** 0 before onset and after the breakdown (vehicle repaired), rising to 1 at breakdown. */
    public double severity(Instant at) {
        if (at.isBefore(onset) || !at.isBefore(failure)) return 0;
        double total = Duration.between(onset, failure).toSeconds();
        return strength * Duration.between(onset, at).toSeconds() / total;
    }

    public static long seed(String vin) {
        long h = 1125899906842597L;
        for (int i = 0; i < vin.length(); i++) h = 31 * h + vin.charAt(i);
        return h;
    }
}
