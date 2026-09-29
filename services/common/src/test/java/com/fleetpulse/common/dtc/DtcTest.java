package com.fleetpulse.common.dtc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DtcTest {

    @ParameterizedTest
    @CsvSource({
            "0301, P0301",
            "0A80, P0A80",
            "4035, C0035",
            "8123, B0123",
            "C100, U0100",
            "D121, U1121"
    })
    void decodesRawTwoByteCodes(String hex, String expected) {
        assertThat(Dtc.fromRaw(Integer.parseInt(hex, 16))).isEqualTo(expected);
        assertThat(Dtc.toRaw(expected)).isEqualTo(Integer.parseInt(hex, 16));
    }

    @Test
    void rawRoundTripsForEveryCode() {
        for (int raw = 0; raw <= 0xFFFF; raw += 7) {
            assertThat(Dtc.toRaw(Dtc.fromRaw(raw))).isEqualTo(raw);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "P0301,    P0301",
            "p0301,    P0301",
            "' P0420 ', P0420",
            "0x0301,   P0301",
            "c100,     U0100"
    })
    void normalisesTextAndHex(String in, String expected) {
        assertThat(Dtc.normalise(in)).isEqualTo(expected);
    }

    @Test
    void normaliseRejectsGarbage() {
        assertThat(Dtc.normalise(null)).isNull();
        assertThat(Dtc.normalise("  ")).isNull();
        assertThat(Dtc.normalise("P4301")).isNull();   // first digit must be 0-3
        assertThat(Dtc.normalise("X0301")).isNull();
        assertThat(Dtc.normalise("ENGINE")).isNull();
    }

    @Test
    void extractsCodesFromFreeText() {
        assertThat(Dtc.extract("MIL on: p0301 and P0420; repeat P0301. Also junk P9999"))
                .containsExactly("P0301", "P0420");
        assertThat(Dtc.extract("")).isEmpty();
        assertThat(Dtc.extract(null)).isEmpty();
    }

    @Test
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> Dtc.fromRaw(0x10000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Dtc.toRaw("P9999")).isInstanceOf(IllegalArgumentException.class);
    }
}
