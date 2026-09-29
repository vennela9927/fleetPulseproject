package com.fleetpulse.gateway;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal circuit breaker around the Kafka producer.
 * CLOSED: calls go through. After {@code threshold} consecutive failures it OPENs and
 * rejects immediately for {@code openFor}, so a dead broker costs callers a fast 503
 * instead of a slow timeout. Then it lets one trial call through (HALF_OPEN); success
 * closes it, failure opens it again.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final Duration openFor;
    private final Clock clock;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong(-1);

    public CircuitBreaker(int threshold, Duration openFor, Clock clock) {
        this.threshold = threshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    public State state() {
        long at = openedAt.get();
        if (at < 0) return State.CLOSED;
        return clock.millis() - at >= openFor.toMillis() ? State.HALF_OPEN : State.OPEN;
    }

    public boolean allowRequest() {
        return state() != State.OPEN;
    }

    public void recordSuccess() {
        failures.set(0);
        openedAt.set(-1);
    }

    public void recordFailure() {
        if (state() == State.HALF_OPEN || failures.incrementAndGet() >= threshold) {
            openedAt.set(clock.millis());
        }
    }

    public long retryAfterSeconds() {
        long at = openedAt.get();
        if (at < 0) return 0;
        return Math.max(1, (openFor.toMillis() - (clock.millis() - at) + 999) / 1000);
    }
}
