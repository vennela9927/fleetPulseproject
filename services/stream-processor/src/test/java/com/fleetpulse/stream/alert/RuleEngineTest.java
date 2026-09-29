package com.fleetpulse.stream.alert;

import com.fleetpulse.common.event.EventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    final RuleEngine engine = new RuleEngine(Set.of("P0217", "P0A80"),
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC));
    VehicleRuleState state = new VehicleRuleState();

    /** Feeds events through the engine, round-tripping the state through bytes each time like the store does. */
    List<Alert> feed(Events... events) {
        List<Alert> out = new ArrayList<>();
        for (Events e : events) {
            out.addAll(engine.evaluate(state, e.build()));
            state = VehicleRuleState.fromBytes(state.toBytes());
        }
        return out;
    }

    List<AlertRule> rules(List<Alert> alerts) {
        return alerts.stream().map(Alert::rule).toList();
    }

    @Test
    void healthyDrivingRaisesNothing() {
        List<Events> drive = new ArrayList<>();
        for (int s = 0; s < 3600; s += 10) drive.add(Events.at(s));
        assertThat(feed(drive.toArray(Events[]::new))).isEmpty();
    }

    @Test
    void overheatFiresOnceAfterThirtySecondsWithTheEpisodeStartAsIdentity() {
        List<Alert> alerts = feed(
                Events.at(0).coolant(111), Events.at(10).coolant(113), Events.at(20).coolant(114),
                Events.at(30).coolant(115), Events.at(40).coolant(116), Events.at(50).coolant(117));
        assertThat(rules(alerts)).containsExactly(AlertRule.ENGINE_OVERHEAT);
        Alert a = alerts.get(0);
        assertThat(a.openedAt()).isEqualTo(Events.T0);
        assertThat(a.eventTs()).isEqualTo(Events.T0.plusSeconds(30));
        assertThat(a.dedupKey()).isEqualTo(Events.VIN + ":ENGINE_OVERHEAT:" + Events.T0.toEpochMilli());
        assertThat(a.severity()).isEqualTo("CRITICAL");
        assertThat(a.details()).containsEntry("above_for_seconds", 30L);
    }

    @Test
    void overheatNeedsTheConditionToHoldContinuously() {
        assertThat(feed(Events.at(0).coolant(112), Events.at(10).coolant(112), Events.at(20).coolant(109),
                Events.at(30).coolant(112), Events.at(40).coolant(112), Events.at(50).coolant(112))).isEmpty();
    }

    @Test
    void aSecondOverheatEpisodeInsideTheCooldownIsSuppressedAndAfterItFires() {
        List<Alert> first = feed(Events.at(0).coolant(112), Events.at(30).coolant(112));
        List<Alert> during = feed(Events.at(60).coolant(95), Events.at(70).coolant(112), Events.at(100).coolant(112));
        List<Alert> after = feed(Events.at(1000).coolant(95), Events.at(1010).coolant(112), Events.at(1040).coolant(112));
        assertThat(first).hasSize(1);
        assertThat(during).isEmpty();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).openedAt()).isEqualTo(Events.T0.plusSeconds(1010));
    }

    @Test
    void aReportingGapEndsTheEpisodeInsteadOfBridgingIt() {
        // Hot, then silent for 10 minutes, then hot again: not 10 minutes of overheating.
        assertThat(feed(Events.at(0).coolant(112), Events.at(600).coolant(112))).isEmpty();
    }

    @Test
    void idlingFiresAfterMoreThanTenMinutes() {
        List<Events> idle = new ArrayList<>();
        for (int s = 0; s <= 610; s += 10) idle.add(Events.at(s).speed(0));
        List<Alert> alerts = feed(idle.toArray(Events[]::new));
        assertThat(rules(alerts)).containsExactly(AlertRule.EXCESSIVE_IDLING);
        assertThat(alerts.get(0).eventTs()).isEqualTo(Events.T0.plusSeconds(610));
        assertThat(alerts.get(0).severity()).isEqualTo("INFO");
    }

    @Test
    void parkedWithEngineOffIsNotIdling() {
        List<Events> parked = new ArrayList<>();
        for (int s = 0; s <= 1200; s += 10) parked.add(Events.at(s).engineOff());
        assertThat(feed(parked.toArray(Events[]::new))).isEmpty();
    }

    @Test
    void criticalFaultCodeFiresAndOrdinaryCodesDoNot() {
        assertThat(feed(Events.at(0).dtc("P0301", "P0420"))).isEmpty();
        List<Alert> alerts = feed(Events.at(10).dtc("P0301", "P0217"));
        assertThat(rules(alerts)).containsExactly(AlertRule.CRITICAL_DTC);
        assertThat(alerts.get(0).details()).containsEntry("dtc", "P0217");
    }

    @Test
    void lowTwelveVoltOnlyCountsWithTheIgnitionOff() {
        assertThat(feed(Events.at(0).battV(11.5))).isEmpty();   // engine running: charging system load
        assertThat(rules(feed(Events.at(10).engineOff().battV(11.5)))).containsExactly(AlertRule.LOW_12V_BATTERY);
        assertThat(feed(Events.at(20).engineOff().battV(11.4))).isEmpty();   // cooldown
    }

    @Test
    void evLowChargeWhileDrivingFiresButNotWhileStopped() {
        assertThat(feed(Events.at(0).ev(8).speed(0))).isEmpty();
        assertThat(rules(feed(Events.at(10).ev(8).speed(30)))).containsExactly(AlertRule.EV_LOW_SOC);
    }

    @Test
    void hybridLowChargeIsNotAnEvAlert() {
        Events hybrid = Events.at(0).speed(40);
        hybrid.soc = 8.0;   // reports fuel as well, so it is not battery-electric
        assertThat(feed(hybrid)).isEmpty();
    }

    @Test
    void fiveHarshEventsInTenMinutesFireAndTheWindowRestarts() {
        List<Alert> alerts = feed(
                Events.at(0).evt(EventType.HARSH_BRAKE), Events.at(100).evt(EventType.HARSH_ACCEL),
                Events.at(200).evt(EventType.HARSH_BRAKE), Events.at(300).evt(EventType.HARSH_BRAKE),
                Events.at(400).evt(EventType.HARSH_ACCEL));
        assertThat(rules(alerts)).containsExactly(AlertRule.HARSH_DRIVING);
        assertThat(alerts.get(0).openedAt()).isEqualTo(Events.T0);
        assertThat(state.harshCount).isZero();
    }

    @Test
    void harshEventsSpreadOverMoreThanTenMinutesDoNotFire() {
        assertThat(feed(
                Events.at(0).evt(EventType.HARSH_BRAKE), Events.at(200).evt(EventType.HARSH_BRAKE),
                Events.at(400).evt(EventType.HARSH_BRAKE), Events.at(600).evt(EventType.HARSH_BRAKE),
                Events.at(800).evt(EventType.HARSH_BRAKE))).isEmpty();
    }

    @Test
    void aLateHarshEventStillCountsInItsOwnTimeSlot() {
        List<Alert> alerts = feed(
                Events.at(0).evt(EventType.HARSH_BRAKE), Events.at(100).evt(EventType.HARSH_BRAKE),
                Events.at(300).evt(EventType.HARSH_BRAKE), Events.at(400).evt(EventType.HARSH_BRAKE),
                Events.at(200).evt(EventType.HARSH_BRAKE));   // arrives late
        assertThat(rules(alerts)).containsExactly(AlertRule.HARSH_DRIVING);
    }

    @Test
    void aLateEventDoesNotMoveAnEpisode() {
        feed(Events.at(0).coolant(112), Events.at(20).coolant(112));
        // A late, cool reading from before the episode must not end it.
        assertThat(feed(Events.at(5).coolant(90))).isEmpty();
        assertThat(rules(feed(Events.at(30).coolant(112)))).containsExactly(AlertRule.ENGINE_OVERHEAT);
    }

    @Test
    void replayingTheSameEventsFromTheSameStateGivesTheSameDedupKeys() {
        byte[] before = state.toBytes();
        Events[] seq = {Events.at(0).coolant(112), Events.at(30).coolant(112), Events.at(40).dtc("P0A80")};
        List<String> first = feed(seq).stream().map(Alert::dedupKey).toList();
        state = VehicleRuleState.fromBytes(before);
        List<String> replay = feed(seq).stream().map(Alert::dedupKey).toList();
        assertThat(first).hasSize(2).isEqualTo(replay);
    }

    @Test
    void stateSurvivesSerialisationAndUnknownVersionsStartFresh() {
        feed(Events.at(0).coolant(112), Events.at(10).coolant(112).evt(EventType.HARSH_BRAKE));
        VehicleRuleState copy = VehicleRuleState.fromBytes(state.toBytes());
        assertThat(copy.overheatSince).isEqualTo(Events.T0.toEpochMilli());
        assertThat(copy.harshCount).isEqualTo(1);
        assertThat(copy.lastTs).isEqualTo(state.lastTs);

        byte[] future = state.toBytes();
        future[0] = 99;
        assertThat(VehicleRuleState.fromBytes(future).lastTs).isZero();
        assertThat(VehicleRuleState.fromBytes(null).lastTs).isZero();
    }
}
