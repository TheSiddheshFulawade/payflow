package com.siddheshfulawade.payflow.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.risk.domain.VelocityWindow.Outcome;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/** Random sequences of checks, duplicates and time steps against the window. */
class VelocityWindowPropertyTest {

    private static final long WINDOW = 10_000;
    private static final UUID[] IDS = new UUID[8];

    static {
        for (int i = 0; i < IDS.length; i++) {
            IDS[i] = UUID.randomUUID();
        }
    }

    // REQ-PF-031 the window never holds more than `limit` entries, whatever the sequence
    @Property
    void sizeNeverExceedsTheLimit(
            @ForAll @Size(max = 80) List<@IntRange(min = 0, max = 7) Integer> payments,
            @ForAll @IntRange(min = 1, max = 5) int limit,
            @ForAll @LongRange(min = 0, max = 4_000) long step) {
        VelocityWindow window = VelocityWindow.empty();
        long now = 0;

        for (int payment : payments) {
            now += step;
            window = window.add(IDS[payment], now, limit, WINDOW).window();

            assertThat(window.size()).isLessThanOrEqualTo(limit);
        }
    }

    // REQ-PF-032 asking again right after an allowed check is allowed and changes nothing
    @Property
    void anAllowedPaymentAskedAgainIsFreeAndChangesNothing(
            @ForAll @Size(max = 80) List<@IntRange(min = 0, max = 7) Integer> payments,
            @ForAll @IntRange(min = 1, max = 5) int limit,
            @ForAll @LongRange(min = 0, max = 4_000) long step) {
        VelocityWindow window = VelocityWindow.empty();
        long now = 0;

        for (int payment : payments) {
            now += step;
            Outcome first = window.add(IDS[payment], now, limit, WINDOW);
            window = first.window();
            if (first.allowed()) {
                Outcome again = window.add(IDS[payment], now, limit, WINDOW);

                assertThat(again.allowed()).isTrue();
                assertThat(again.window().size()).isEqualTo(window.size());
            }
        }
    }

    // REQ-PF-031 a refusal happens only when the window is full, and stores nothing
    @Property
    void aRefusalOnlyHappensWhenFullAndStoresNothing(
            @ForAll @Size(max = 80) List<@IntRange(min = 0, max = 7) Integer> payments,
            @ForAll @IntRange(min = 1, max = 3) int limit,
            @ForAll @LongRange(min = 0, max = 2_000) long step) {
        VelocityWindow window = VelocityWindow.empty();
        long now = 0;

        for (int payment : payments) {
            now += step;
            Outcome outcome = window.add(IDS[payment], now, limit, WINDOW);

            if (!outcome.allowed()) {
                assertThat(outcome.window().size()).as("refused only when full").isEqualTo(limit);
                assertThat(outcome.window().size()).isLessThanOrEqualTo(window.size());
            }
            window = outcome.window();
        }
    }
}
