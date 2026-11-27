package com.siddheshfulawade.payflow.payment.domain;

import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.NOW;
import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Command;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** What the sweeper does with an overdue payment: re-send up to maxResends times, then compensate (REQ-PF-012). */
class SagaOverdueTest {

    private static final int MAX_RESENDS = 3;

    private static Command currentCommand(Payment p) {
        return switch (p.state()) {
            case RESERVING -> new ReserveFunds(p.id(), "alice", 2_500, "INR", "local");
            case RISK_CHECKING -> new CheckRisk(p.id(), "alice", 2_500, "INR");
            case CAPTURING -> new CaptureFunds(p.id(), "alice", "bob", 2_500, "INR");
            case RELEASING -> new ReleaseFunds(p.id(), "alice", 2_500);
            default -> throw new IllegalArgumentException(p.state().name());
        };
    }

    private static Overdue.Compensate timeoutRelease(Payment p) {
        return new Overdue.Compensate(new Transition(
                PaymentState.RELEASING, Optional.of(new ReleaseFunds(p.id(), "alice", 2_500)), "TIMEOUT"));
    }

    static Stream<PaymentState> stepStates() {
        return Stream.of(PaymentState.RESERVING, PaymentState.RISK_CHECKING, PaymentState.CAPTURING);
    }

    @ParameterizedTest
    @MethodSource("stepStates")
    @DisplayName("REQ-PF-012 attempts 1 to 3 re-send the step's own command; attempt 4 (3 re-sends used) compensates")
    void resendsThreeTimesThenCompensates(PaymentState state) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            Payment p = payment(state, attempt);

            assertThat(SagaStateMachine.overdue(p, MAX_RESENDS))
                    .as("%s attempt %d", state, attempt)
                    .isEqualTo(new Overdue.Resend(currentCommand(p)));
        }
        for (int attempt = 4; attempt <= 8; attempt++) {
            Payment p = payment(state, attempt);

            assertThat(SagaStateMachine.overdue(p, MAX_RESENDS))
                    .as("%s attempt %d", state, attempt)
                    .isEqualTo(timeoutRelease(p));
        }
    }

    @ParameterizedTest
    @MethodSource("stepStates")
    @DisplayName("REQ-PF-012 the limit is the parameter: maxResends 0 compensates at once, 5 re-sends five times")
    void limitFollowsTheParameter(PaymentState state) {
        for (int maxResends : IntStream.rangeClosed(0, 5).toArray()) {
            for (int attempt = 1; attempt <= 7; attempt++) {
                Payment p = payment(state, attempt);

                Overdue expected = attempt <= maxResends ? new Overdue.Resend(currentCommand(p)) : timeoutRelease(p);
                assertThat(SagaStateMachine.overdue(p, maxResends))
                        .as("%s attempt %d max %d", state, attempt, maxResends)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("REQ-PF-012 a release is re-sent forever, however high the attempt and low the limit")
    void releaseIsNeverGivenUp() {
        for (int attempt : new int[] {1, 2, 3, 4, 5, 10, 100, 10_000}) {
            for (int maxResends : new int[] {0, 1, 3, 100}) {
                Payment p = payment(PaymentState.RELEASING, attempt);

                assertThat(SagaStateMachine.overdue(p, maxResends)).isEqualTo(new Overdue.Resend(currentCommand(p)));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RECEIVED", "COMPLETED", "REVERSED", "REJECTED"})
    @DisplayName("REQ-PF-012 a payment that waits for nothing is never overdue: asking is a bug")
    void nothingToSweep(PaymentState state) {
        assertThatIllegalStateException().isThrownBy(() -> SagaStateMachine.overdue(payment(state), MAX_RESENDS));
    }

    @Test
    @DisplayName("REQ-PF-012 a re-sent command is exactly the command the forward transition produced")
    void resendEqualsTheOriginalCommand() {
        Payment received = payment(PaymentState.RECEIVED);
        Payment reserving = received.movedTo(PaymentState.RESERVING, NOW.plusSeconds(10), NOW);
        Payment checking = reserving.movedTo(PaymentState.RISK_CHECKING, NOW.plusSeconds(10), NOW);
        Payment capturing = checking.movedTo(PaymentState.CAPTURING, NOW.plusSeconds(10), NOW);

        assertThat(SagaStateMachine.overdue(reserving, MAX_RESENDS))
                .isEqualTo(new Overdue.Resend(
                        SagaStateMachine.start(received).command().orElseThrow()));
        assertThat(SagaStateMachine.overdue(checking, MAX_RESENDS))
                .isEqualTo(new Overdue.Resend(SagaStateMachine.on(reserving, new FundsReserved(reserving.id()), 1)
                        .orElseThrow()
                        .command()
                        .orElseThrow()));
        assertThat(SagaStateMachine.overdue(capturing, MAX_RESENDS))
                .isEqualTo(new Overdue.Resend(SagaStateMachine.on(checking, new RiskApproved(checking.id()), 1)
                        .orElseThrow()
                        .command()
                        .orElseThrow()));
    }
}
