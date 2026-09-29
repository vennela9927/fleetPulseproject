package com.fleetpulse.gateway;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void opensHalfOpensAndCloses() {
        MutableClock clock = new MutableClock();
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofSeconds(5), clock);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.retryAfterSeconds()).isZero();

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.allowRequest()).isTrue();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse();
        assertThat(cb.retryAfterSeconds()).isEqualTo(5);

        clock.now = clock.now.plusSeconds(5);
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordFailure();                                   // trial call failed: open again
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.now = clock.now.plusSeconds(6);
        cb.recordSuccess();                                   // trial call succeeded: close
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
