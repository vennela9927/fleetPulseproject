package com.fleetpulse.simulator.fleet;

import java.util.List;

/**
 * The simulated fleet mix. Ids match {@code vehicle_model} rows in 03_reference_data.sql.
 * Shares add up to 1.0 and decide how many of the N vehicles get each model.
 */
public final class Catalog {

    public enum Powertrain { ICE, HYBRID, EV }

    public record Model(int id, String oem, String wmi, String vds, Powertrain powertrain,
                        double share, double rangeKm) {}

    public static final List<Model> MODELS = List.of(
            new Model(1, "AURORA",   "AUR", "MT4C2", Powertrain.ICE,    0.15, 600),
            new Model(2, "AURORA",   "AUR", "CG7L1", Powertrain.ICE,    0.12, 800),
            new Model(3, "AURORA",   "AUR", "VT3E5", Powertrain.EV,     0.08, 380),
            new Model(4, "BOREALIS", "BRL", "HL2D4", Powertrain.ICE,    0.18, 900),
            new Model(5, "BOREALIS", "BRL", "HY5B8", Powertrain.HYBRID, 0.12, 700),
            new Model(6, "CYGNUS",   "CYG", "EV7A1", Powertrain.EV,     0.15, 420),
            new Model(7, "CYGNUS",   "CYG", "EC1S6", Powertrain.EV,     0.18, 260),
            new Model(8, "DRACO",    "DRC", "TT9K3", Powertrain.ICE,    0.02, 1200));

    private Catalog() {}

    /** Deterministic model for the i-th of n vehicles, spread in proportion to share. */
    public static Model modelFor(int index, int total) {
        double position = (index + 0.5) / total;
        double cumulative = 0;
        for (Model m : MODELS) {
            cumulative += m.share();
            if (position < cumulative) return m;
        }
        return MODELS.get(MODELS.size() - 1);
    }

    public static Model byId(int id) {
        return MODELS.stream().filter(m -> m.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown model " + id));
    }
}
