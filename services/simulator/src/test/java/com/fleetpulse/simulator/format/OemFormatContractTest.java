package com.fleetpulse.simulator.format;

import com.fleetpulse.common.event.CanonicalEvent;
import com.fleetpulse.common.event.Json;
import com.fleetpulse.common.mapping.EventMapper;
import com.fleetpulse.common.mapping.MappingSpec;
import com.fleetpulse.common.vin.Vin;
import com.fleetpulse.simulator.fleet.Catalog;
import com.fleetpulse.simulator.model.FaultPlan;
import com.fleetpulse.simulator.model.VehicleSim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Contract between the simulator (producer of OEM formats) and the normalizer (consumer):
 * every payload the simulator emits must map back to the values it was generated from,
 * through the same mapping specs production uses.
 */
class OemFormatContractTest {

    static EventMapper mapper(String resource) throws Exception {
        try (InputStream in = OemFormatContractTest.class.getResourceAsStream("/mappings/" + resource + ".json")) {
            return EventMapper.compile(Json.MAPPER.readValue(in, MappingSpec.class));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyModelRoundTripsThroughItsMapping(int modelId) throws Exception {
        Catalog.Model m = Catalog.byId(modelId);
        EventMapper mapper = mapper(m.oem().equals("DRACO") ? "draco-test" : m.oem().toLowerCase());
        String vin = Vin.generate(m.wmi(), m.vds(), 2024, 'A', modelId * 1000);
        Instant t = Instant.parse("2026-09-29T08:00:00Z");
        VehicleSim v = new VehicleSim(vin, m, 12.97, 77.59, t, 1_000);
        v.inject(FaultPlan.Component.ELECTRICAL, t, Duration.ofMinutes(30));   // exercise fault codes too

        for (int i = 0; i < 400; i++) {
            t = t.plusSeconds(10);
            VehicleSim.Sample s = v.step(t, 10);
            CanonicalEvent e = mapper.map(OemFormatter.format(s), t);

            assertThat(e.vin()).isEqualTo(vin);
            assertThat(e.oem()).isEqualTo(m.oem());
            assertThat(e.seq()).isEqualTo(s.seq());
            // Draco sends second precision; the others keep milliseconds.
            assertThat(Duration.between(e.ts(), s.ts()).abs()).isLessThan(Duration.ofSeconds(1));
            assertThat(e.lat()).isCloseTo(s.lat(), within(1e-9));
            assertThat(e.lon()).isCloseTo(s.lon(), within(1e-9));
            assertThat(e.speedKmh()).isCloseTo(s.speedKmh(), within(0.1));
            assertThat(e.odoKm()).isCloseTo(s.odoKm(), within(0.1));
            assertThat(e.engineOn()).isEqualTo(s.engineOn());
            assertThat(e.dtc()).containsExactlyElementsOf(s.dtc().stream().distinct().toList());
            assertThat(e.evt()).isEqualTo(s.evt());
            if (s.coolantC() != null) assertThat(e.coolantC()).isCloseTo(s.coolantC(), within(0.05));
            if (s.socPct() != null && m.powertrain() == Catalog.Powertrain.EV)
                assertThat(e.socPct()).isCloseTo(s.socPct(), within(0.5));
        }
    }
}
