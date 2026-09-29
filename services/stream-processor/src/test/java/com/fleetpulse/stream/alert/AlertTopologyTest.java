package com.fleetpulse.stream.alert;

import com.fleetpulse.common.event.Json;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real topology against an in-memory driver: no broker needed. */
class AlertTopologyTest {

    SimpleMeterRegistry metrics;
    TopologyTestDriver driver;
    TestInputTopic<String, byte[]> canonical;
    TestOutputTopic<String, byte[]> alerts;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "alert-detector-test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
        driver = new TopologyTestDriver(AlertTopology.build(new RuleEngine(Set.of("P0217"), Clock.systemUTC()), metrics), p);
        canonical = driver.createInputTopic(AlertTopology.CANONICAL, Serdes.String().serializer(), Serdes.ByteArray().serializer());
        alerts = driver.createOutputTopic(AlertTopology.ALERTS, Serdes.String().deserializer(), Serdes.ByteArray().deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    void send(Events e) {
        canonical.pipeInput(e.vin, Json.write(e.build()));
    }

    @Test
    void anOverheatEpisodeProducesOneAlertKeyedByVin() {
        for (int s = 0; s <= 60; s += 10) send(Events.at(s).coolant(115));
        List<org.apache.kafka.streams.test.TestRecord<String, byte[]>> out = alerts.readRecordsToList();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).key()).isEqualTo(Events.VIN);
        Alert a = Json.read(out.get(0).value(), Alert.class);
        assertThat(a.rule()).isEqualTo(AlertRule.ENGINE_OVERHEAT);
        assertThat(a.dedupKey()).isEqualTo(Events.VIN + ":ENGINE_OVERHEAT:" + Events.T0.toEpochMilli());
        assertThat(metrics.counter("alerts_raised_total", "rule", "ENGINE_OVERHEAT").count()).isEqualTo(1);
    }

    @Test
    void stateIsPerVehicle() {
        Events other = Events.at(10).coolant(115);
        other.vin = "5YJ3E1EA7KF317000";
        send(Events.at(0).coolant(115));
        send(other);                           // a different vehicle: must not continue the episode
        send(Events.at(20).coolant(115));
        assertThat(alerts.isEmpty()).isTrue();
        send(Events.at(30).coolant(115));
        assertThat(alerts.readRecordsToList()).hasSize(1);
    }

    @Test
    void anUnreadableEventIsSkippedAndCounted() {
        canonical.pipeInput(Events.VIN, "{not json".getBytes(StandardCharsets.UTF_8));
        send(Events.at(0).dtc("P0217"));
        assertThat(alerts.readRecordsToList()).hasSize(1);
        assertThat(metrics.counter("alert_detector_unreadable_events_total").count()).isEqualTo(1);
    }
}
