package com.fleetpulse.normalizer;

import java.nio.ByteBuffer;

/**
 * Per-vehicle de-duplication state: the last {@value #CAPACITY} sequence numbers seen,
 * in a ring buffer serialised to 8 * CAPACITY + 4 bytes.
 *
 * <p>Membership is a linear scan over 64 longs (a few nanoseconds, cache-resident), which
 * beats a hash set at this size and has a fixed memory cost: 100K vehicles × 516 B ≈ 52 MB
 * of state. Duplicates older than the window are rare (retries happen within seconds)
 * and are still absorbed downstream by the idempotent sinks, so the window only has to
 * catch the common case exactly.
 */
final class SeqWindow {

    static final int CAPACITY = 64;
    private final long[] seqs;
    private int next;
    private int size;

    SeqWindow() {
        seqs = new long[CAPACITY];
    }

    private SeqWindow(long[] seqs, int next, int size) {
        this.seqs = seqs;
        this.next = next;
        this.size = size;
    }

    /** Records {@code seq}; returns false if it was already present (a duplicate). */
    boolean add(long seq) {
        for (int i = 0; i < size; i++) if (seqs[i] == seq) return false;
        seqs[next] = seq;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;
        return true;
    }

    byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + 8 * size);
        b.putShort((short) next).putShort((short) size);
        for (int i = 0; i < size; i++) b.putLong(seqs[i]);
        return b.array();
    }

    static SeqWindow fromBytes(byte[] bytes) {
        if (bytes == null) return new SeqWindow();
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int next = b.getShort(), size = b.getShort();
        long[] seqs = new long[CAPACITY];
        for (int i = 0; i < size; i++) seqs[i] = b.getLong();
        return new SeqWindow(seqs, next, size);
    }
}
