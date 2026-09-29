package com.fleetpulse.stream.alert;

import java.time.Duration;

/**
 * The rules evaluated on the live stream. Codes and severities mirror the {@code alert_rule}
 * reference table (a test keeps them in step). Rules that need history or a model
 * (battery degradation, failure risk, parking clusters) run in batch jobs, not here.
 *
 * <p>The cooldown stops a flapping signal from raising the same alert over and over:
 * after a rule fires for a vehicle it stays quiet for that vehicle until the cooldown ends.
 */
public enum AlertRule {
    ENGINE_OVERHEAT("CRITICAL", Duration.ofMinutes(15)),
    CRITICAL_DTC("CRITICAL", Duration.ofHours(1)),
    LOW_12V_BATTERY("WARNING", Duration.ofHours(6)),
    HARSH_DRIVING("WARNING", Duration.ofMinutes(30)),
    EV_LOW_SOC("WARNING", Duration.ofHours(1)),
    EXCESSIVE_IDLING("INFO", Duration.ofMinutes(30)),
    SENSOR_FAULT("WARNING", Duration.ofHours(6));

    public final String severity;
    public final Duration cooldown;

    AlertRule(String severity, Duration cooldown) {
        this.severity = severity;
        this.cooldown = cooldown;
    }
}
