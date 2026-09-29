package com.fleetpulse.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param clickhouseBatchRows a partition's batch is written once it holds this many events
 * @param clickhouseFlushMs   ...or once it is this old, which bounds how stale ClickHouse is
 */
@ConfigurationProperties(prefix = "stream")
public record StreamProps(
        String kafkaBootstrap,
        int streamThreads,
        int replicationFactor,
        String stateDir,
        String redisUrl,
        String clickhouseUrl,
        String clickhouseUser,
        String clickhousePassword,
        int clickhouseBatchRows,
        long clickhouseFlushMs) {
}
