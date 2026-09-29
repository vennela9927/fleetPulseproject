package com.fleetpulse.stream.alert;

import java.time.Instant;
import java.util.Map;

/**
 * An alert as published on the {@code alerts} topic.
 *
 * @param dedupKey   deterministic identity (vin, rule, start of the episode that fired it), so
 *                   a replayed event re-derives the same key and the Postgres sink ignores it
 * @param openedAt   when the condition began (episode start), which can precede {@code eventTs}
 * @param eventTs    vehicle timestamp of the event that crossed the threshold
 * @param ingestTs   when the normaliser accepted that event; with {@code detectedAt} it
 *                   gives the pipeline's own share of the alert latency
 * @param detectedAt wall-clock time the rule fired (informational, not part of the identity)
 */
public record Alert(
        String dedupKey,
        String vin,
        AlertRule rule,
        String severity,
        Instant openedAt,
        Instant eventTs,
        Instant ingestTs,
        Instant detectedAt,
        Map<String, Object> details) {

    static Alert of(AlertRule rule, String vin, long episodeStartMs, Instant eventTs, Instant ingestTs,
                    Instant detectedAt, Map<String, Object> details) {
        return new Alert(vin + ":" + rule.name() + ":" + episodeStartMs, vin, rule, rule.severity,
                Instant.ofEpochMilli(episodeStartMs), eventTs, ingestTs, detectedAt, details);
    }
}
