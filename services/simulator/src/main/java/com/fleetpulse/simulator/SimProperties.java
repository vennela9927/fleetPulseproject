package com.fleetpulse.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param modes             any of seed, backfill, run, executed in that order
 * @param vehicles          fleet size
 * @param intervalSeconds   each vehicle reports once per interval (10 s → 10K events/s for 100K vehicles)
 * @param sink              http (through the ingest gateway) or kafka (straight to telemetry.raw, for load tests)
 * @param duplicateRate     share of events sent twice (tests idempotency)
 * @param outOfOrderRate    share of events delayed by 5-30 s (tests event-time windows)
 * @param invalidRate       share of events corrupted on purpose (tests validation and the DLQ)
 */
@ConfigurationProperties(prefix = "sim")
public record SimProperties(
        List<String> modes,
        int vehicles,
        int intervalSeconds,
        String sink,
        String gatewayUrl,
        String apiKey,
        String kafkaBootstrap,
        int batchSize,
        int maxInFlight,
        double duplicateRate,
        double outOfOrderRate,
        double invalidRate,
        int backfillDays,
        int backfillSampleMinutes,
        int backfillThreads,
        String clickhouseUrl,
        String clickhouseUser,
        String clickhousePassword) {
}
