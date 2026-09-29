package com.fleetpulse.simulator.model;

import com.fleetpulse.common.vin.Vin;
import com.fleetpulse.simulator.fleet.Catalog;
import com.fleetpulse.simulator.fleet.Catalog.Powertrain;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FaultPlanTest {

    static final Instant ANCHOR = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void isDeterministicPerVin() {
        String vin = Vin.generate("AUR", "MT4C2", 2024, 'A', 123);
        assertThat(FaultPlan.forVehicle(vin, Powertrain.ICE, ANCHOR))
                .isEqualTo(FaultPlan.forVehicle(vin, Powertrain.ICE, ANCHOR));
    }

    @Test
    void aboutFivePercentOfVehiclesHaveAFaultInsideTheWindow() {
        int n = 20_000, faulty = 0;
        for (int i = 0; i < n; i++) {
            Catalog.Model m = Catalog.modelFor(i, n);
            FaultPlan p = FaultPlan.forVehicle(Vin.generate(m.wmi(), m.vds(), 2024, 'A', i), m.powertrain(), ANCHOR);
            if (p == null) continue;
            faulty++;
            assertThat(p.component().appliesTo(m.powertrain())).isTrue();
            assertThat(p.failure()).isBetween(ANCHOR.minus(FaultPlan.HISTORY), ANCHOR.plus(FaultPlan.FUTURE));
            assertThat(Duration.between(p.onset(), p.failure()).toHours()).isBetween(72L, 240L);
        }
        assertThat(faulty / (double) n).isCloseTo(0.05, within(0.01));
    }

    @Test
    void severityRisesFromOnsetToFailureThenResets() {
        Instant onset = ANCHOR;
        FaultPlan p = new FaultPlan(FaultPlan.Component.COOLING, onset, onset.plus(Duration.ofDays(4)), 1.0);
        assertThat(p.severity(onset.minusSeconds(1))).isZero();
        assertThat(p.severity(onset.plus(Duration.ofDays(2)))).isCloseTo(0.5, within(1e-9));
        assertThat(p.severity(onset.plus(Duration.ofDays(4)))).isZero();   // repaired after breakdown
    }

    @Test
    void faultShowsUpInSignals() {
        Catalog.Model m = Catalog.byId(1);
        String vin = Vin.generate(m.wmi(), m.vds(), 2024, 'A', 5);
        Instant t = ANCHOR;
        VehicleSim healthy = new VehicleSim(vin, m, 12.97, 77.59, t, 0);
        VehicleSim failing = new VehicleSim(vin, m, 12.97, 77.59, t, 0);
        failing.inject(FaultPlan.Component.COOLING, t, Duration.ofHours(2));
        double maxHealthy = 0, maxFailing = 0;
        int codes = 0;
        for (int i = 0; i < 700; i++) {
            t = t.plusSeconds(10);
            VehicleSim.Sample h = healthy.step(t, 10), f = failing.step(t, 10);
            if (h.coolantC() != null) maxHealthy = Math.max(maxHealthy, h.coolantC());
            if (f.coolantC() != null) maxFailing = Math.max(maxFailing, f.coolantC());
            codes += (int) f.dtc().stream().filter(c -> c.equals("P0128") || c.equals("P0217")).count();
        }
        assertThat(maxFailing).isGreaterThan(maxHealthy + 5);
        assertThat(codes).isPositive();
    }

    /** Longest run of consecutive 10 s samples above 110 C during a 3-minute cooling fault, in seconds. */
    static int longestOverheatSeconds(VehicleSim v, Instant start, boolean demo) {
        FaultPlan p = new FaultPlan(FaultPlan.Component.COOLING, start, start.plus(Duration.ofMinutes(3)), 1.0);
        if (demo) v.inject(p.component(), start, Duration.ofMinutes(3));
        else v.schedule(p);
        int run = 0, best = 0;
        for (Instant t = start.plusSeconds(10); !t.isAfter(start.plus(Duration.ofMinutes(3))); t = t.plusSeconds(10)) {
            VehicleSim.Sample s = v.step(t, 10);
            run = s.coolantC() != null && s.coolantC() > 110 ? run + 10 : 0;
            best = Math.max(best, run);
        }
        return best;
    }

    @Test
    void aDemoCoolingFaultOverheatsLongEnoughToAlertButANaturalOneDoesNot() {
        Catalog.Model m = Catalog.byId(1);
        int demoAlerting = 0, naturalAlerting = 0;
        for (int i = 0; i < 40; i++) {
            String vin = Vin.generate(m.wmi(), m.vds(), 2024, 'A', 1000 + i);
            // Warm the engines up for an hour first, as on the road.
            Instant t = ANCHOR;
            VehicleSim demo = new VehicleSim(vin, m, 12.97, 77.59, t, 0);
            VehicleSim natural = new VehicleSim(vin, m, 12.97, 77.59, t, 0);
            for (int k = 0; k < 360; k++) {
                t = t.plusSeconds(10);
                demo.step(t, 10);
                natural.step(t, 10);
            }
            if (longestOverheatSeconds(demo, t, true) >= 30) demoAlerting++;
            if (longestOverheatSeconds(natural, t, false) >= 30) naturalAlerting++;
        }
        // Parked vehicles cannot overheat, so not all 40; most running ones do.
        assertThat(demoAlerting).isGreaterThanOrEqualTo(10);
        assertThat(naturalAlerting).isZero();
    }
}
