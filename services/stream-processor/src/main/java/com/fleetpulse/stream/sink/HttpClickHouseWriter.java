package com.fleetpulse.stream.sink;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Inserts gzipped JSONEachRow into {@code fleet.telemetry} over ClickHouse's HTTP interface,
 * with insert deduplication on for the table and the rollup views it feeds.
 */
final class HttpClickHouseWriter implements TelemetryBatcher.Writer {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String auth;

    HttpClickHouseWriter(String baseUrl, String user, String password) {
        this.baseUrl = baseUrl;
        this.auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void insert(byte[] gzippedRows, String dedupToken) throws Exception {
        String url = baseUrl + "/?query=" + enc("INSERT INTO fleet.telemetry FORMAT JSONEachRow")
                + "&insert_deduplicate=1&deduplicate_blocks_in_dependent_materialized_views=1"
                + "&insert_deduplication_token=" + enc(dedupToken);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Encoding", "gzip")
                .header("Authorization", auth)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzippedRows))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ClickHouse insert failed (" + resp.statusCode() + "): "
                    + resp.body().lines().findFirst().orElse(""));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
