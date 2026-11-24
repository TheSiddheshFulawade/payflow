package com.siddheshfulawade.payflow.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.risk.domain.VelocityWindow.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The sliding window of one payer, as a pure value: no clock, no Hazelcast. Limits are inclusive. */
class VelocityWindowTest {

    private static final long WINDOW = 60_000;
    private static final int LIMIT = 5;

    private final UUID[] id = new UUID[10];

    VelocityWindowTest() {
        for (int i = 0; i < id.length; i++) {
            id[i] = UUID.randomUUID();
        }
    }

    private static Outcome add(VelocityWindow window, UUID payment, long now) {
        return window.add(payment, now, LIMIT, WINDOW);
    }

    /** Adds ids 0..count-1 at the given time; all must be allowed. */
    private VelocityWindow filled(int count, long now) {
        VelocityWindow window = VelocityWindow.empty();
        for (int i = 0; i < count; i++) {
            Outcome outcome = add(window, id[i], now);
            assertThat(outcome.allowed()).as("payment %d of %d", i + 1, count).isTrue();
            window = outcome.window();
        }
        return window;
    }

    @Test
    @DisplayName("REQ-PF-031 an empty window has no entries")
    void emptyWindow() {
        assertThat(VelocityWindow.empty().size()).isZero();
    }

    @Test
    @DisplayName("REQ-PF-031 the 5th payment in the window is allowed and the 6th is refused (limit is inclusive)")
    void fifthAllowedSixthRefused() {
        VelocityWindow full = filled(5, 0);

        Outcome sixth = add(full, id[5], 1);

        assertThat(full.size()).isEqualTo(5);
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.window().size()).isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-PF-031 an entry exactly 60 s old is dropped, so the next payment is allowed")
    void entryExactlyOneWindowOldDrops() {
        VelocityWindow full = filled(5, 0);

        Outcome next = add(full, id[5], 60_000);

        assertThat(next.allowed()).isTrue();
        assertThat(next.window().size())
                .as("all five expired, only the new one is left")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-031 an entry 59.999 s old still counts, so the next payment is refused")
    void entryJustInsideTheWindowStays() {
        VelocityWindow full = filled(5, 0);

        Outcome next = add(full, id[5], 59_999);

        assertThat(next.allowed()).isFalse();
        assertThat(next.window().size()).isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-PF-032 a payment already in the window is allowed and free, even when the window is full")
    void duplicateIsFreeWhenFull() {
        VelocityWindow full = filled(5, 0);

        Outcome again = add(full, id[2], 10);

        assertThat(again.allowed()).isTrue();
        assertThat(again.window().size()).isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-PF-032 a duplicate does not refresh the timestamp: the first check decides when it expires")
    void duplicateKeepsItsFirstTimestamp() {
        // id0 at t=0; id1..id4 at t=30 s; window full
        VelocityWindow window = add(VelocityWindow.empty(), id[0], 0).window();
        for (int i = 1; i < 5; i++) {
            window = add(window, id[i], 30_000).window();
        }

        window = add(window, id[0], 50_000).window(); // redelivery of the first one, 10 s before it expires
        Outcome next = add(window, id[5], 60_000);

        assertThat(next.allowed())
                .as("id0 is 60 s old now: dropped; a refreshed timestamp would keep it and refuse this one")
                .isTrue();
        assertThat(next.window().size()).isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-PF-032 a payment whose entry expired is new again and takes a slot with a fresh timestamp")
    void expiredDuplicateCountsAsNew() {
        VelocityWindow full = filled(5, 0);

        Outcome again = add(full, id[0], 60_000);

        assertThat(again.allowed()).isTrue();
        assertThat(again.window().size()).isEqualTo(1);
        assertThat(add(again.window(), id[0], 60_000 + 59_999).window().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-031 a refused payment is not stored: it takes no slot when room appears later")
    void refusedPaymentIsNotStored() {
        VelocityWindow window = add(VelocityWindow.empty(), id[0], 0).window(); // expires at 60 s
        for (int i = 1; i < 5; i++) {
            window = add(window, id[i], 30_000).window();
        }
        Outcome refused = add(window, id[5], 1_000);
        assertThat(refused.allowed()).as("full: refused").isFalse();

        // id0 expires; one slot is free. id6 takes it; id5 must NOT be in the window (else id6 would be refused)
        Outcome newcomer = add(refused.window(), id[6], 60_000);

        assertThat(newcomer.allowed()).isTrue();
        assertThat(newcomer.window().size()).isEqualTo(5);
        assertThat(add(newcomer.window(), id[5], 60_000).allowed())
                .as("id5 asks again: the window is full again, so it is refused again")
                .isFalse();
    }

    @Test
    @DisplayName("REQ-PF-031 a refused payment can be allowed after the window moves on")
    void refusedThenAllowedAfterExpiry() {
        VelocityWindow full = filled(5, 0);
        assertThat(add(full, id[5], 59_999).allowed()).isFalse();

        Outcome retry = add(full, id[5], 60_000);

        assertThat(retry.allowed()).isTrue();
    }

    @Test
    @DisplayName("REQ-PF-032 add never changes the window it is called on (the value is immutable)")
    void addDoesNotMutate() {
        VelocityWindow window = filled(3, 0);

        add(window, id[3], 1);
        add(window, id[0], 1);

        assertThat(window.size()).isEqualTo(3);
        assertThat(add(window, id[3], 2).allowed()).isTrue();
        assertThat(add(window, id[4], 2).allowed()).isTrue();
    }
}
