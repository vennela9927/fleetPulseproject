package com.fleetpulse.simulator.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetpulse.common.event.Json;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Posts batches to the ingest gateway the way an OEM cloud would push them.
 *
 * <p>Back-pressure: at most {@code maxInFlight} requests are outstanding. When the gateway
 * answers 429/503 the batch is retried after {@code Retry-After} (or exponential backoff),
 * and the permit is held meanwhile, so a struggling gateway slows the producer down instead
 * of losing data. Nothing is ever dropped.
 */
public final class HttpSink implements Sink {

    private static final Logger log = LoggerFactory.getLogger(HttpSink.class);

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String apiKey;
    private final Semaphore inFlight;
    private final int maxInFlight;
    private final Counter accepted, throttled, failed;

    public HttpSink(String baseUrl, String apiKey, int maxInFlight, MeterRegistry metrics) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.maxInFlight = maxInFlight;
        this.inFlight = new Semaphore(maxInFlight);
        this.accepted = metrics.counter("sim_batches_accepted_total");
        this.throttled = metrics.counter("sim_batches_throttled_total");
        this.failed = metrics.counter("sim_batches_failed_total");
    }

    @Override
    public void send(String oem, List<ObjectNode> batch) throws InterruptedException {
        byte[] body = Json.write(batch);
        inFlight.acquire();
        attempt(oem, body, 0);
    }

    private void attempt(String oem, byte[] body, int tries) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/ingest/" + oem.toLowerCase()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        client.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, err) -> {
            int status = err == null ? resp.statusCode() : -1;
            if (status >= 200 && status < 300) {
                accepted.increment();
                inFlight.release();
                return;
            }
            long delayMs;
            if (status == 429 || status == 503) {
                throttled.increment();
                delayMs = retryAfterMs(resp);
            } else {
                failed.increment();
                delayMs = Math.min(10_000, 200L << Math.min(tries, 6));
                if (tries % 10 == 0) log.warn("batch for {} failed (status {}, {}), retrying", oem, status,
                        err == null ? "" : err.getMessage());
            }
            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS)
                    .execute(() -> attempt(oem, body, tries + 1));
        });
    }

    private static long retryAfterMs(HttpResponse<?> resp) {
        try {
            return resp.headers().firstValue("Retry-After").map(s -> Long.parseLong(s.trim()) * 1000).orElse(500L);
        } catch (NumberFormatException e) {
            return 500L;
        }
    }

    public int inFlight() {
        return maxInFlight - inFlight.availablePermits();
    }
}
