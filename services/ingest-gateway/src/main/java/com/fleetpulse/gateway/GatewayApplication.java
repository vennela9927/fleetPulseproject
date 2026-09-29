package com.fleetpulse.gateway;

import com.fleetpulse.common.mapping.MappingRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Set;

@SpringBootApplication
@EnableConfigurationProperties(GatewayApplication.GatewayProperties.class)
public class GatewayApplication {

    /**
     * @param apiKeySha256       SHA-256 (hex) of the partner API key; the key itself is never stored
     * @param oems               OEMs allowed to push data (onboarded or pending onboarding)
     * @param maxBatch           largest accepted batch
     * @param maxPendingRecords  records handed to Kafka but not yet acknowledged; above this we answer 429
     */
    @ConfigurationProperties(prefix = "gateway")
    public record GatewayProperties(String apiKeySha256, Set<String> oems, int maxBatch,
                                    long maxPendingRecords, String kafkaBootstrap) {}

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    @Profile("!test")
    MappingRegistry mappingRegistry(GatewayProperties props) throws InterruptedException {
        return new MappingRegistry().start(props.kafkaBootstrap(), Duration.ofSeconds(20));
    }
}
