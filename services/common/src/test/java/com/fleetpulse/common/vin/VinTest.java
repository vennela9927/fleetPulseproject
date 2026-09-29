package com.fleetpulse.common.vin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VinTest {

    @Test
    void acceptsKnownRealWorldVin() {
        // Example from the problem statement; its check digit (position 9) is '3'.
        assertThat(Vin.isValid("1HGCM82633A004352")).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            ",                    VIN_MISSING",
            "1HGCM82633A00435,    VIN_LENGTH",
            "1HGCM82633A0043521,  VIN_LENGTH",
            "1HGCM8263IA004352,   VIN_ILLEGAL_CHAR",
            "1HGCM8263OA004352,   VIN_ILLEGAL_CHAR",
            "1HGCM8263QA004352,   VIN_ILLEGAL_CHAR",
            "1hgcm82633a004352,   VIN_ILLEGAL_CHAR",
            "1HGCM82643A004352,   VIN_CHECK_DIGIT"
    })
    void rejectsInvalidVins(String vin, String reason) {
        Vin.Check c = Vin.check(vin);
        assertThat(c.valid()).isFalse();
        assertThat(c.reason()).isEqualTo(reason);
    }

    @Test
    void generatedVinsAreValidAndDeterministic() {
        for (int serial = 0; serial < 5_000; serial++) {
            String vin = Vin.generate("AUR", "MT4C2", 2024, 'B', serial);
            assertThat(Vin.isValid(vin)).as(vin).isTrue();
            assertThat(Vin.wmi(vin)).isEqualTo("AUR");
        }
        assertThat(Vin.generate("CYG", "EV7A1", 2026, 'C', 42))
                .isEqualTo(Vin.generate("CYG", "EV7A1", 2026, 'C', 42));
    }

    @Test
    void checkDigitCanBeX() {
        boolean sawX = false;
        for (int serial = 0; serial < 200 && !sawX; serial++) {
            sawX = Vin.generate("BRL", "HL2D4", 2022, 'A', serial).charAt(8) == 'X';
        }
        assertThat(sawX).isTrue();
    }

    @Test
    void generateRejectsBadInput() {
        assertThatThrownBy(() -> Vin.generate("AU", "MT4C2", 2024, 'B', 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vin.generate("AUR", "MT4C2", 2009, 'B', 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vin.generate("AUR", "MT4C2", 2024, 'B', 1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Vin.generate("AUR", "MT4CO", 2024, 'B', 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
