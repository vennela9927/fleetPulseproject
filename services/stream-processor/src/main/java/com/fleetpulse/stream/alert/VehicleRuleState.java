package com.fleetpulse.stream.alert;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * What the rule engine remembers about one vehicle between events, serialised to a fixed
 * {@value #BYTES} bytes for the state store (100K vehicles ≈ 12 MB).
 *
 * <p>All times are vehicle timestamps in epoch milliseconds; 0 means "not set".
 */
final class VehicleRuleState {

    private static final byte VERSION = 1;
    static final int HARSH_SLOTS = 5;
    private static final int RULES = AlertRule.values().length;
    static final int BYTES = 1 + 8 * 3 + 2 + 1 + 8 * HARSH_SLOTS + 8 * RULES;

    /** Latest in-order event; later events with an earlier timestamp are "late". */
    long lastTs;
    long overheatSince;
    long idleSince;
    boolean overheatRaised;
    boolean idleRaised;
    /** Most recent harsh-event timestamps, ascending; only the first {@code harshCount} are valid. */
    final long[] harsh = new long[HARSH_SLOTS];
    int harshCount;
    final long[] lastRaised = new long[RULES];

    boolean inCooldown(AlertRule rule, long ts) {
        long last = lastRaised[rule.ordinal()];
        return last != 0 && ts - last < rule.cooldown.toMillis();
    }

    void raised(AlertRule rule, long ts) {
        lastRaised[rule.ordinal()] = Math.max(lastRaised[rule.ordinal()], ts);
    }

    /** Inserts in time order, keeping the {@value #HARSH_SLOTS} most recent. */
    void addHarsh(long ts) {
        if (harshCount == HARSH_SLOTS) {
            if (ts <= harsh[0]) return;   // older than everything we keep
            System.arraycopy(harsh, 1, harsh, 0, HARSH_SLOTS - 1);
            harshCount--;
        }
        int i = harshCount;
        while (i > 0 && harsh[i - 1] > ts) {
            harsh[i] = harsh[i - 1];
            i--;
        }
        harsh[i] = ts;
        harshCount++;
    }

    void clearHarsh() {
        Arrays.fill(harsh, 0);
        harshCount = 0;
    }

    byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        b.put(VERSION).putLong(lastTs).putLong(overheatSince).putLong(idleSince);
        b.put((byte) (overheatRaised ? 1 : 0)).put((byte) (idleRaised ? 1 : 0));
        b.put((byte) harshCount);
        for (long t : harsh) b.putLong(t);
        for (long t : lastRaised) b.putLong(t);
        return b.array();
    }

    static VehicleRuleState fromBytes(byte[] bytes) {
        VehicleRuleState s = new VehicleRuleState();
        if (bytes == null) return s;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.get() != VERSION) return s;   // unknown layout: start fresh rather than misread
        s.lastTs = b.getLong();
        s.overheatSince = b.getLong();
        s.idleSince = b.getLong();
        s.overheatRaised = b.get() == 1;
        s.idleRaised = b.get() == 1;
        s.harshCount = b.get();
        for (int i = 0; i < HARSH_SLOTS; i++) s.harsh[i] = b.getLong();
        for (int i = 0; i < RULES; i++) s.lastRaised[i] = b.getLong();
        return s;
    }
}
