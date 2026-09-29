package com.fleetpulse.normalizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeqWindowTest {

    @Test
    void detectsDuplicatesAndSurvivesSerialisation() {
        SeqWindow w = new SeqWindow();
        assertThat(w.add(1)).isTrue();
        assertThat(w.add(3)).isTrue();
        assertThat(w.add(2)).isTrue();    // out of order is fine
        assertThat(w.add(3)).isFalse();

        SeqWindow copy = SeqWindow.fromBytes(w.toBytes());
        assertThat(copy.add(2)).isFalse();
        assertThat(copy.add(4)).isTrue();
        assertThat(SeqWindow.fromBytes(null).add(1)).isTrue();
    }

    @Test
    void evictsOldestWhenFull() {
        SeqWindow w = new SeqWindow();
        for (long s = 0; s < SeqWindow.CAPACITY; s++) assertThat(w.add(s)).isTrue();
        assertThat(w.add(0)).isFalse();
        assertThat(w.add(SeqWindow.CAPACITY)).isTrue();   // evicts seq 0
        assertThat(w.add(0)).isTrue();                    // outside the window now
        SeqWindow copy = SeqWindow.fromBytes(w.toBytes());
        assertThat(copy.add(SeqWindow.CAPACITY)).isFalse();
    }
}
