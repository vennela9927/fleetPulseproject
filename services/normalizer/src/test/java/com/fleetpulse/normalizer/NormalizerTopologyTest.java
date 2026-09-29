package com.fleetpulse.normalizer;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.MappingRegistry;
import com.fleetpulse.common.vin.Vin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real topology against an in-memory driver: no broker needed. */
class NormalizerTopologyTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    static final String AUR = Vin.generate("AUR", "MT4C2", 2024, 'A', 1);
    static final String DRC = Vin.generate("DRC", "TT9K3", 2025, 'D', 2);

    MappingRegistry registry;
    SimpleMeterRegistry metrics;
    TopologyTestDriver driver;
    TestInputTopic<byte[], byte[]> raw;
    TestOutputTopic<String, byte[]> canonical;
    TestOutputTopic<byte[], byte[]> dlq;

    @BeforeEach
    void setUp() throws Exception {
        registry = new MappingRegistry();
        try (InputStream in = getClass().getResourceAsStream("/mappings/aurora.json")) {
            registry.apply("AURORA", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        metrics = new SimpleMeterRegistry();
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "normalizer-test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        driver = new TopologyTestDriver(
                NormalizerTopology.build(registry, metrics, Clock.fixed(NOW, ZoneOffset.UTC)), p);
        raw = driver.createInputTopic(NormalizerTopology.RAW, Serdes.ByteArray().serializer(), Serdes.ByteArray().serializer());
        canonical = driver.createOutputTopic(NormalizerTopology.CANONICAL, Serdes.String().deserializer(), Serdes.ByteArray().deserializer());
        dlq = driver.createOutputTopic(NormalizerTopology.DLQ, Serdes.ByteArray().deserializer(), Serdes.ByteArray().deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    void send(String oem, String vin, String json) {
        RecordHeaders h = new RecordHeaders();
        h.add("oem", oem.getBytes(StandardCharsets.UTF_8));
        raw.pipeInput(new TestRecord<>(vin == null ? null : vin.getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8), h, NOW));
    }

    static String aurora(String vin, long seq) {
        return """
                {"vin":"%s","seq":%d,"timestamp":"2026-09-29T09:59:50Z","location":{"lat":12.97,"lng":77.59},
                 "speed":{"value":40},"engine":{"on":true,"rpm":1500,"coolant_c":91},"dtcs":["P0301"]}
                """.formatted(vin, seq);
    }

    static String header(TestRecord<byte[], byte[]> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void validEventIsNormalisedAndKeyedByVin() {
        send("AURORA", AUR, aurora(AUR, 7));
        TestRecord<String, byte[]> out = canonical.readRecord();
        CanonicalEvent e = Json.read(out.value(), CanonicalEvent.class);
        assertThat(out.key()).isEqualTo(AUR);
        assertThat(e.seq()).isEqualTo(7);
        assertThat(e.ingestTs()).isEqualTo(NOW);
        assertThat(e.dtc()).containsExactly("P0301");
        assertThat(out.timestamp()).isEqualTo(Instant.parse("2026-09-29T09:59:50Z").toEpochMilli());   // event time
        assertThat(dlq.isEmpty()).isTrue();
    }

    @Test
    void duplicatesAreDroppedButOutOfOrderEventsPass() {
        send("AURORA", AUR, aurora(AUR, 10));
        send("AURORA", AUR, aurora(AUR, 10));   // retry duplicate
        send("AURORA", AUR, aurora(AUR, 8));    // late, but new
        send("AURORA", AUR, aurora(AUR, 10));   // duplicate again
        assertThat(canonical.readValuesToList()).hasSize(2);
        assertThat(metrics.counter("normalizer_duplicates_dropped_total", "oem", "AURORA").count()).isEqualTo(2);
    }

    @Test
    void invalidAndMalformedPayloadsGoToDlqWithReason() {
        send("AURORA", "x", aurora("AUR00000000000000", 1));
        send("AURORA", null, "{not json");
        TestRecord<byte[], byte[]> bad = dlq.readRecord();
        assertThat(header(bad, "dlq_reason")).isEqualTo("VIN_CHECK_DIGIT");
        assertThat(header(bad, "oem")).isEqualTo("AURORA");
        assertThat(header(dlq.readRecord(), "dlq_reason")).isEqualTo("MALFORMED_JSON");
        assertThat(canonical.isEmpty()).isTrue();
    }

    @Test
    void unmappedOemIsParkedThenFlowsOnceOnboarded() throws Exception {
        String draco = """
                {"truck":{"vin_no":"%s","telemetry":{"pos":{"latitude":28.6,"longitude":77.2},"velocity_kmh":50}},
                 "sequence":1,"sent_at":"2026-09-29 09:59:55"}""".formatted(DRC);
        send("DRACO", null, draco);
        TestRecord<byte[], byte[]> parked = dlq.readRecord();
        assertThat(header(parked, "dlq_reason")).isEqualTo("UNMAPPED_OEM");
        assertThat(new String(parked.value(), StandardCharsets.UTF_8)).isEqualTo(draco);   // original payload kept

        // Onboarding: a mapping arrives on the registry; no restart.
        registry.apply("DRACO", """
                {"oem":"DRACO","version":1,"wmi":"DRC","fields":{
                  "vin":{"path":"truck.vin_no"},"seq":{"path":"sequence"},
                  "ts":{"path":"sent_at","transform":"datetime","format":"yyyy-MM-dd HH:mm:ss"},
                  "lat":{"path":"truck.telemetry.pos.latitude"},"lon":{"path":"truck.telemetry.pos.longitude"},
                  "speedKmh":{"path":"truck.telemetry.velocity_kmh"}}}""");
        send("DRACO", DRC, draco);
        CanonicalEvent e = Json.read(canonical.readValue(), CanonicalEvent.class);
        assertThat(e.oem()).isEqualTo("DRACO");
        assertThat(e.vin()).isEqualTo(DRC);
        assertThat(e.speedKmh()).isEqualTo(50.0);
    }
}
