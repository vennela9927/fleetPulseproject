package com.fleetpulse.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.EventMapper;
import com.fleetpulse.common.mapping.MappingRegistry;
import com.fleetpulse.gateway.GatewayApplication.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST /v1/ingest/{oem}} — an OEM cloud pushes a JSON array of raw events.
 *
 * <p>Responses: 202 once every record is acknowledged by Kafka (so 202 means durable);
 * 400 malformed body; 401 bad key; 404 unknown OEM; 413 batch too large;
 * 429 + Retry-After when too many records await acknowledgement (back-pressure);
 * 503 + Retry-After when the broker is failing (circuit breaker open).
 *
 * <p>The gateway is stateless: it does not parse or validate event contents beyond
 * finding the VIN for the partition key. Validation happens in the normalizer, where a
 * failure goes to the DLQ instead of being rejected, so nothing an OEM sends is lost.
 */
@RestController
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final GatewayProperties props;
    private final RawPublisher publisher;
    private final MappingRegistry registry;
    private final CircuitBreaker breaker;
    private final MeterRegistry metrics;
    private final Timer latency;
    private final byte[] expectedKeyHash;

    public IngestController(GatewayProperties props, RawPublisher publisher, MappingRegistry registry, MeterRegistry metrics) {
        this.props = props;
        this.publisher = publisher;
        this.registry = registry;
        this.metrics = metrics;
        this.breaker = new CircuitBreaker(5, Duration.ofSeconds(5), Clock.systemUTC());
        this.latency = Timer.builder("gateway_batch_latency").publishPercentiles(0.5, 0.95, 0.99).register(metrics);
        this.expectedKeyHash = HexFormat.of().parseHex(props.apiKeySha256());
    }

    @PostMapping(path = "/v1/ingest/{oem}", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> ingest(@PathVariable String oem,
                                                      @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                      @RequestBody JsonNode body) {
        long start = System.nanoTime();
        String code = oem.toUpperCase(Locale.ROOT);
        if (!authorised(apiKey)) return reject(HttpStatus.UNAUTHORIZED, "invalid API key", code, 0);
        if (!props.oems().contains(code)) return reject(HttpStatus.NOT_FOUND, "unknown OEM", code, 0);
        if (!body.isArray() || body.isEmpty()) return reject(HttpStatus.BAD_REQUEST, "body must be a non-empty JSON array", code, 0);
        if (body.size() > props.maxBatch())
            return reject(HttpStatus.PAYLOAD_TOO_LARGE, "batch larger than " + props.maxBatch(), code, 0);
        if (!breaker.allowRequest())
            return reject(HttpStatus.SERVICE_UNAVAILABLE, "broker unavailable", code, breaker.retryAfterSeconds());
        if (!publisher.hasCapacity(body.size()))
            return reject(HttpStatus.TOO_MANY_REQUESTS, "back-pressure: retry shortly", code, 1);

        EventMapper mapper = registry.get(code);   // null until the OEM is onboarded; payloads are still kept
        List<RawPublisher.Raw> records = new ArrayList<>(body.size());
        for (JsonNode payload : body) {
            String vin = null;
            if (mapper != null) {
                try {
                    vin = mapper.vinOf(payload);
                } catch (RuntimeException ignored) {
                    // unusable VIN: no partition key, the normalizer will send it to the DLQ
                }
            }
            records.add(new RawPublisher.Raw(vin, Json.write(payload)));
        }
        String rid = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        try {
            publisher.publishAndAwait(code, rid, records);
            breaker.recordSuccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return reject(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", code, 1);
        } catch (Exception e) {
            breaker.recordFailure();
            log.warn("publish failed for {} ({} records): {}", code, records.size(), e.toString());
            return reject(HttpStatus.SERVICE_UNAVAILABLE, "broker unavailable", code, 2);
        }
        metrics.counter("gateway_records_accepted_total", "oem", code).increment(records.size());
        latency.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        return ResponseEntity.accepted().body(Map.of("accepted", records.size(), "requestId", rid));
    }

    @GetMapping("/v1/ingest/status")
    public Map<String, Object> status() {
        return Map.of("circuit", breaker.state().name(), "mappings", registry.versions());
    }

    private boolean authorised(String apiKey) {
        if (apiKey == null) return false;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(hash, expectedKeyHash);   // constant-time comparison
        } catch (Exception e) {
            return false;
        }
    }

    private ResponseEntity<Map<String, Object>> reject(HttpStatus status, String reason, String oem, long retryAfter) {
        metrics.counter("gateway_rejected_total", "status", String.valueOf(status.value()), "oem", oem).increment();
        var b = ResponseEntity.status(status);
        if (retryAfter > 0) b.header("Retry-After", String.valueOf(retryAfter));
        return b.body(Map.of("error", reason));
    }
}
