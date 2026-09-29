package com.fleetpulse.common.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GeohashTest {

    @Test
    void encodesKnownPoints() {
        assertThat(Geohash.encode(57.64911, 10.40744, 11)).isEqualTo("u4pruydqqvj");  // reference value
        assertThat(Geohash.encode(12.9716, 77.5946, 6)).isEqualTo("tdr1v9");          // Bengaluru
    }

    @Test
    void nearbyPointsSharePrefix() {
        String a = Geohash.encode(12.9716, 77.5946, 6);
        String b = Geohash.encode(12.9720, 77.5950, 6);
        assertThat(a.substring(0, 5)).isEqualTo(b.substring(0, 5));
    }

    @Test
    void rejectsBadPrecision() {
        assertThatThrownBy(() -> Geohash.encode(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Geohash.encode(0, 0, 13)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void haversineDistance() {
        // Bengaluru → Chennai is about 290 km in a straight line.
        double d = Geohash.distanceMetres(12.9716, 77.5946, 13.0827, 80.2707);
        assertThat(d).isCloseTo(290_000, within(5_000.0));
        assertThat(Geohash.distanceMetres(1, 1, 1, 1)).isZero();
    }
}
