package com.siddheshfulawade.payflow.payment.domain;

import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Command;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The saga table of SPEC section 4, checked exhaustively: state x reply type x step attempt x reply attempt. */
class SagaStateMachineTest {

    /** @param command the command class that must go out with the move, null when the payment ends */
    private record Expected(PaymentState to, Class<? extends Command> command, String reason) {}

    /** Every reply type, all for the same payment id. */
    private static List<Reply> allReplies(UUID id) {
        return List.of(
                new FundsReserved(id),
                new FundsRejected(id, "INSUFFICIENT_FUNDS"),
                new RiskApproved(id),
                new RiskRejected(id, "VELOCITY"),
                new FundsCaptured(id),
                new CaptureFailed(id, "PAYEE_FROZEN"),
                new FundsReleased(id));
    }

    /** All the transitions of the saga. Anything not listed here must be ignored. */
    private static Optional<Expected> listed(PaymentState state, Reply reply) {
        return switch (state) {
            case RESERVING ->
                switch (reply) {
                    case FundsReserved r ->
                        Optional.of(new Expected(PaymentState.RISK_CHECKING, CheckRisk.class, null));
                    case FundsRejected r -> Optional.of(new Expected(PaymentState.REJECTED, null, r.reason()));
                    default -> Optional.empty();
                };
            case RISK_CHECKING ->
                switch (reply) {
                    case RiskApproved r -> Optional.of(new Expected(PaymentState.CAPTURING, CaptureFunds.class, null));
                    // the reason is the rule that refused
                    case RiskRejected r ->
                        Optional.of(new Expected(PaymentState.RELEASING, ReleaseFunds.class, r.rule()));
                    default -> Optional.empty();
                };
            case CAPTURING ->
                switch (reply) {
                    case FundsCaptured r -> Optional.of(new Expected(PaymentState.COMPLETED, null, null));
                    case CaptureFailed r ->
                        Optional.of(new Expected(PaymentState.RELEASING, ReleaseFunds.class, r.reason()));
                    default -> Optional.empty();
                };
            case RELEASING ->
                switch (reply) {
                    case FundsReleased r -> Optional.of(new Expected(PaymentState.REVERSED, null, null));
                    // the wallet answers a release of an already captured hold with FundsCaptured: the money moved
                    case FundsCaptured r -> Optional.of(new Expected(PaymentState.COMPLETED, null, null));
                    default -> Optional.empty();
                };
            case RECEIVED, COMPLETED, REVERSED, REJECTED -> Optional.empty();
        };
    }

    static Stream<Arguments> everyStateReplyAndAttempt() {
        return Stream.of(PaymentState.values())
                .flatMap(state -> Stream.of(1, 2, 3)
                        .flatMap(stepAttempts -> allReplies(UUID.randomUUID()).stream()
                                .flatMap(reply -> Stream.of(1, 2, 3)
                                        .map(replyAttempt -> Arguments.of(state, stepAttempts, reply, replyAttempt)))));
    }

    /**
     * While RELEASING, FundsReleased and FundsCaptured are final facts about the hold (REVERSED / COMPLETED): they
     * count whichever attempt they answer, because a release is re-sent forever and its answer is always older than
     * the attempt that is current when it arrives.
     */
    private static boolean finalFactOfTheHold(PaymentState state, Reply reply) {
        return state == PaymentState.RELEASING && (reply instanceof FundsReleased || reply instanceof FundsCaptured);
    }

    @ParameterizedTest(name = "{0} on attempt {1} + {2} attempt {3}")
    @MethodSource("everyStateReplyAndAttempt")
    @DisplayName("REQ-PF-010 only the listed transitions exist, for the current attempt (RELEASING: any attempt for"
            + " FundsReleased and FundsCaptured); all else is ignored")
    void onlyListedTransitionsExist(PaymentState state, int stepAttempts, Reply reply, int replyAttempt) {
        Payment payment = payment(state, stepAttempts);

        Optional<Transition> result = SagaStateMachine.on(payment, reply, replyAttempt);

        Optional<Expected> expected = listed(state, reply);
        boolean attemptCounts = replyAttempt == stepAttempts || finalFactOfTheHold(state, reply);
        if (!attemptCounts || expected.isEmpty()) {
            assertThat(result)
                    .as("%s attempt %d + %s attempt %d", state, stepAttempts, reply, replyAttempt)
                    .isEmpty();
            return;
        }
        assertThat(result).isPresent();
        assertThat(result.get().to()).isEqualTo(expected.get().to());
        if (expected.get().command() == null) {
            assertThat(result.get().command()).as("terminal step sends nothing").isEmpty();
        } else {
            assertThat(result.get().command()).containsInstanceOf(expected.get().command());
        }
        if (expected.get().reason() == null) {
            assertThat(result.get().reason()).isNull();
        } else {
            assertThat(result.get().reason()).contains(expected.get().reason());
        }
    }

    @Test
    @DisplayName(
            "REQ-PF-010 start() moves RECEIVED to RESERVING and asks the wallet to reserve the payment's own values")
    void startReserves() {
        Payment payment = payment(PaymentState.RECEIVED);

        Transition start = SagaStateMachine.start(payment);

        assertThat(start.to()).isEqualTo(PaymentState.RESERVING);
        assertThat(start.command()).contains(new ReserveFunds(payment.id(), "alice", 2_500, "INR", "local"));
    }

    @Test
    @DisplayName("REQ-PF-010 the CheckRisk command is built from the payment row, not from the reply")
    void checkRiskComesFromTheRow() {
        Payment payment = payment(PaymentState.RESERVING);

        Transition t =
                SagaStateMachine.on(payment, new FundsReserved(payment.id()), 1).orElseThrow();

        assertThat(t.command()).contains(new CheckRisk(payment.id(), "alice", 2_500, "INR"));
    }

    @Test
    @DisplayName("REQ-PF-010 the CaptureFunds command carries payer, payee, amount and currency of the row")
    void captureComesFromTheRow() {
        Payment payment = payment(PaymentState.RISK_CHECKING);

        Transition t =
                SagaStateMachine.on(payment, new RiskApproved(payment.id()), 1).orElseThrow();

        assertThat(t.command()).contains(new CaptureFunds(payment.id(), "alice", "bob", 2_500, "INR"));
    }

    @Test
    @DisplayName("REQ-PF-011 a risk rejection releases the payer's own hold: ReleaseFunds from the row, reason = rule")
    void riskRejectionReleasesTheRowsHold() {
        Payment payment = payment(PaymentState.RISK_CHECKING);

        Transition t = SagaStateMachine.on(payment, new RiskRejected(payment.id(), "VELOCITY"), 1)
                .orElseThrow();

        assertThat(t.to()).isEqualTo(PaymentState.RELEASING);
        assertThat(t.command()).contains(new ReleaseFunds(payment.id(), "alice", 2_500));
        assertThat(t.reason()).isEqualTo("VELOCITY");
    }

    @Test
    @DisplayName("REQ-PF-011 a failed capture releases the hold, reason = the wallet's reason")
    void captureFailureReleasesTheHold() {
        Payment payment = payment(PaymentState.CAPTURING);

        Transition t = SagaStateMachine.on(payment, new CaptureFailed(payment.id(), "PAYEE_FROZEN"), 1)
                .orElseThrow();

        assertThat(t.to()).isEqualTo(PaymentState.RELEASING);
        assertThat(t.command()).contains(new ReleaseFunds(payment.id(), "alice", 2_500));
        assertThat(t.reason()).isEqualTo("PAYEE_FROZEN");
    }

    @Test
    @DisplayName("REQ-PF-010 a wallet rejection ends REJECTED, names the reason and sends no command")
    void fundsRejectedEndsRejectedWithReason() {
        Payment payment = payment(PaymentState.RESERVING);

        Transition t = SagaStateMachine.on(payment, new FundsRejected(payment.id(), "INSUFFICIENT_FUNDS"), 1)
                .orElseThrow();

        assertThat(t.to()).isEqualTo(PaymentState.REJECTED);
        assertThat(t.command()).isEmpty();
        assertThat(t.reason()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("REQ-PF-013 on attempt 2 only the attempt 2 reply counts; the late attempt 1 reply is ignored")
    void onlyTheCurrentAttemptCounts() {
        Payment payment = payment(PaymentState.RESERVING, 2);

        assertThat(SagaStateMachine.on(payment, new FundsReserved(payment.id()), 1))
                .isEmpty();
        assertThat(SagaStateMachine.on(payment, new FundsReserved(payment.id()), 3))
                .isEmpty();
        assertThat(SagaStateMachine.on(payment, new FundsReserved(payment.id()), 2))
                .map(Transition::to)
                .contains(PaymentState.RISK_CHECKING);
    }

    @Test
    @DisplayName("REQ-PF-013 a release re-sent to attempt 7 is still ended by the answer to attempt 2")
    void anOldReleaseAnswerEndsTheRelease() {
        Payment payment = payment(PaymentState.RELEASING, 7);

        assertThat(SagaStateMachine.on(payment, new FundsReleased(payment.id()), 2))
                .map(Transition::to)
                .contains(PaymentState.REVERSED);
        assertThat(SagaStateMachine.on(payment, new FundsCaptured(payment.id()), 2))
                .map(Transition::to)
                .contains(PaymentState.COMPLETED);
    }

    @Test
    @DisplayName("REQ-PF-013 outside RELEASING a FundsCaptured of another attempt is still ignored")
    void anOldCaptureAnswerStillCountsOnlyForItsAttempt() {
        Payment payment = payment(PaymentState.CAPTURING, 3);

        assertThat(SagaStateMachine.on(payment, new FundsCaptured(payment.id()), 1))
                .isEmpty();
        assertThat(SagaStateMachine.on(payment, new FundsCaptured(payment.id()), 3))
                .map(Transition::to)
                .contains(PaymentState.COMPLETED);
    }

    @Test
    @DisplayName("REQ-PF-010 exactly COMPLETED, REVERSED and REJECTED are terminal")
    void terminalStates() {
        List<PaymentState> terminal =
                Stream.of(PaymentState.values()).filter(PaymentState::terminal).toList();

        assertThat(terminal)
                .containsExactlyInAnyOrder(PaymentState.COMPLETED, PaymentState.REVERSED, PaymentState.REJECTED);
    }
}
