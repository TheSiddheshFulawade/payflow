package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Step deadlines with backoff: min(base x 2^(attempt - 1), cap) (SPEC section 4, REQ-PF-012). */
class StepDeadlinesTest {

    private static final Duration S = Duration.ofSeconds(1);

    private static StepDeadlines defaults() {
        return new StepDeadlines(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
    }

    static Stream<Arguments> table() {
        return Stream.of(
                // state, attempt, seconds
                Arguments.of(PaymentState.RESERVING, 1, 10),
                Arguments.of(PaymentState.RESERVING, 2, 20),
                Arguments.of(PaymentState.RESERVING, 3, 30),
                Arguments.of(PaymentState.RESERVING, 4, 30),
                Arguments.of(PaymentState.RISK_CHECKING, 1, 5),
                Arguments.of(PaymentState.RISK_CHECKING, 2, 10),
                Arguments.of(PaymentState.RISK_CHECKING, 3, 20),
                Arguments.of(PaymentState.RISK_CHECKING, 4, 30),
                Arguments.of(PaymentState.RISK_CHECKING, 5, 30),
                Arguments.of(PaymentState.CAPTURING, 1, 10),
                Arguments.of(PaymentState.CAPTURING, 2, 20),
                Arguments.of(PaymentState.CAPTURING, 3, 30),
                Arguments.of(PaymentState.RELEASING, 1, 10),
                Arguments.of(PaymentState.RELEASING, 2, 20),
                Arguments.of(PaymentState.RELEASING, 3, 30),
                Arguments.of(PaymentState.RELEASING, 9, 30));
    }

    @ParameterizedTest(name = "{0} attempt {1} = {2} s")
    @MethodSource("table")
    @DisplayName("REQ-PF-012 the deadline doubles with every attempt and stops at the cap")
    void backoffTable(PaymentState state, int attempt, int seconds) {
        assertThat(defaults().in(state, attempt)).contains(S.multipliedBy(seconds));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RESERVING", "RISK_CHECKING", "CAPTURING", "RELEASING"})
    @DisplayName("REQ-PF-012 a huge attempt number gives the cap, without overflow")
    void hugeAttemptsGiveTheCap(PaymentState state) {
        for (int attempt : new int[] {31, 32, 33, 62, 63, 64, 65, 1_000, Integer.MAX_VALUE}) {
            assertThat(defaults().in(state, attempt))
                    .as("%s attempt %d", state, attempt)
                    .contains(Duration.ofSeconds(30));
        }
    }

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @Test
    @DisplayName("REQ-PF-012 the largest allowed settings (10 min) cannot overflow: the result is the cap")
    void largestAllowedValuesDoNotOverflow() {
        var deadlines = new StepDeadlines(TEN_MINUTES, TEN_MINUTES, TEN_MINUTES, TEN_MINUTES, TEN_MINUTES);

        assertThat(deadlines.in(PaymentState.RESERVING, 1)).contains(TEN_MINUTES);
        assertThat(deadlines.in(PaymentState.RESERVING, 100)).contains(TEN_MINUTES);
        assertThat(deadlines.in(PaymentState.RELEASING, Integer.MAX_VALUE)).contains(TEN_MINUTES);
    }

    @Test
    @DisplayName("REQ-PF-012 the defaults are valid: every base and the cap are at most 10 min, the cap is the largest")
    void defaultsAreValid() {
        assertThat(defaults().cap()).isLessThanOrEqualTo(TEN_MINUTES);
        assertThat(Stream.of(
                        defaults().reserve(),
                        defaults().risk(),
                        defaults().capture(),
                        defaults().release()))
                .allSatisfy(
                        base -> assertThat(base).isLessThanOrEqualTo(defaults().cap()));
    }

    @Test
    @DisplayName("REQ-PF-012 a cap equal to a base is allowed: that step never backs off")
    void capEqualToBaseIsAllowed() {
        var deadlines = new StepDeadlines(S, S, S, S, S);

        assertThat(deadlines.in(PaymentState.CAPTURING, 5)).contains(S);
    }

    @ParameterizedTest(name = "base at position {0} above the cap")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("REQ-PF-012 a cap below any base is refused: a step would wait less than its first deadline")
    void capBelowAnyBaseIsRefused(int position) {
        Duration[] values = {Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(4), Duration.ofSeconds(4)
        };
        values[position] = Duration.ofSeconds(10);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepDeadlines(values[0], values[1], values[2], values[3], Duration.ofSeconds(9)))
                .withMessageContaining("payflow.payment.deadlines.");
    }

    @ParameterizedTest(name = "value at position {0} above 10 min")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("REQ-PF-012 no base and no cap may exceed 10 min: a mistyped setting would park payments for hours")
    void anythingAboveTenMinutesIsRefused(int position) {
        Duration[] values = {S, S, S, S, TEN_MINUTES};
        values[position] = TEN_MINUTES.plusSeconds(1);
        if (position < 4) {
            values[4] = TEN_MINUTES.plusSeconds(1); // keep cap >= base, so only the 10 min limit can refuse
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepDeadlines(values[0], values[1], values[2], values[3], values[4]))
                .withMessageContaining("payflow.payment.deadlines.");
    }

    @ParameterizedTest(name = "base at position {0} = 10 min")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("REQ-PF-012 exactly 10 min is allowed for a base, with a cap of 10 min")
    void tenMinutesIsAllowed(int position) {
        Duration[] values = {S, S, S, S};
        values[position] = TEN_MINUTES;

        var deadlines = new StepDeadlines(values[0], values[1], values[2], values[3], TEN_MINUTES);

        assertThat(deadlines.cap()).isEqualTo(TEN_MINUTES);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RECEIVED", "COMPLETED", "REVERSED", "REJECTED"})
    @DisplayName("REQ-PF-012 a state that waits for nothing has no deadline, whatever the attempt")
    void noDeadlineWithoutAWait(PaymentState state) {
        assertThat(defaults().in(state, 1)).isEqualTo(Optional.empty());
        assertThat(defaults().in(state, 7)).isEqualTo(Optional.empty());
    }

    static Stream<Arguments> everyArgumentBad() {
        Duration ok = S;
        return Stream.of(0, 1, 2, 3, 4)
                .flatMap(position -> Stream.of(Duration.ZERO, Duration.ofSeconds(-1), null)
                        .map(bad -> Arguments.of(position, bad, ok)));
    }

    @ParameterizedTest(name = "argument {0} = {1}")
    @MethodSource("everyArgumentBad")
    @DisplayName("REQ-PF-012 a zero, negative or missing duration is refused, for each of the five settings")
    void nonPositiveIsRefused(int position, Duration bad, Duration ok) {
        Duration[] values = {ok, ok, ok, ok, ok};
        values[position] = bad;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StepDeadlines(values[0], values[1], values[2], values[3], values[4]))
                .withMessageContaining("payflow.payment.deadlines.");
    }
}
