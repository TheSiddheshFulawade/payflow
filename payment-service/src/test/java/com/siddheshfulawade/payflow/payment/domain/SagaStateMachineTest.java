package com.siddheshfulawade.payflow.payment.domain;

import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
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

/** The saga table of SPEC section 4, checked exhaustively: state x reply type x attempt match. */
class SagaStateMachineTest {

    private record Expected(PaymentState to, Class<?> command) {}

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

    /** The transitions PF-05 defines. Anything not listed here must be ignored. */
    private static Optional<Expected> listed(PaymentState state, Reply reply) {
        if (state == PaymentState.RESERVING && reply instanceof FundsReserved) {
            return Optional.of(new Expected(PaymentState.RISK_CHECKING, CheckRisk.class));
        }
        if (state == PaymentState.RESERVING && reply instanceof FundsRejected) {
            return Optional.of(new Expected(PaymentState.REJECTED, null));
        }
        if (state == PaymentState.RISK_CHECKING && reply instanceof RiskApproved) {
            return Optional.of(new Expected(PaymentState.CAPTURING, CaptureFunds.class));
        }
        if (state == PaymentState.CAPTURING && reply instanceof FundsCaptured) {
            return Optional.of(new Expected(PaymentState.COMPLETED, null));
        }
        return Optional.empty();
    }

    /** Compensation belongs to PF-06; PF-05 only promises that these never complete a payment. */
    private static boolean compensationPair(PaymentState state, Reply reply) {
        return (state == PaymentState.RISK_CHECKING && reply instanceof RiskRejected)
                || (state == PaymentState.CAPTURING && reply instanceof CaptureFailed)
                || (state == PaymentState.RELEASING && reply instanceof FundsReleased);
    }

    static Stream<Arguments> everyStateReplyAndAttempt() {
        return Stream.of(PaymentState.values())
                .flatMap(state -> allReplies(UUID.randomUUID()).stream()
                        .flatMap(reply -> Stream.of(1, 2).map(attempt -> Arguments.of(state, reply, attempt))));
    }

    @ParameterizedTest(name = "{0} + {1} attempt {2}")
    @MethodSource("everyStateReplyAndAttempt")
    @DisplayName("REQ-PF-010 only the listed transitions exist; every other state, reply and attempt is ignored")
    void onlyListedTransitionsExist(PaymentState state, Reply reply, int replyAttempt) {
        Payment payment = payment(state, 1);

        Optional<Transition> result = SagaStateMachine.on(payment, reply, replyAttempt);

        if (compensationPair(state, reply)) {
            assertThat(result.map(Transition::to))
                    .as("never completes")
                    .isNotEqualTo(Optional.of(PaymentState.COMPLETED));
            return;
        }
        Optional<Expected> expected = listed(state, reply);
        if (replyAttempt != payment.stepAttempts() || expected.isEmpty()) {
            assertThat(result)
                    .as("%s + %s attempt %d", state, reply, replyAttempt)
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
    }

    @Test
    @DisplayName(
            "REQ-PF-010 start() moves RECEIVED to RESERVING and asks the wallet to reserve the payment's own values")
    void startReserves() {
        Payment payment = payment(PaymentState.RECEIVED);

        Transition start = SagaStateMachine.start(payment);

        assertThat(start.to()).isEqualTo(PaymentState.RESERVING);
        assertThat(start.command()).contains(new ReserveFunds(payment.id(), "alice", 2_500, "INR"));
    }

    @Test
    @DisplayName("REQ-PF-010 a RECEIVED payment ignores every reply")
    void receivedIgnoresReplies() {
        Payment payment = payment(PaymentState.RECEIVED);

        for (Reply reply : allReplies(payment.id())) {
            assertThat(SagaStateMachine.on(payment, reply, 1))
                    .as(reply.toString())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("REQ-PF-010 the CheckRisk command is built from the payment row, not from the reply")
    void checkRiskComesFromTheRow() {
        Payment payment = payment(PaymentState.RESERVING);

        Transition t =
                SagaStateMachine.on(payment, new FundsReserved(payment.id()), 1).orElseThrow();

        assertThat(t.to()).isEqualTo(PaymentState.RISK_CHECKING);
        assertThat(t.command()).contains(new CheckRisk(payment.id(), "alice", 2_500, "INR"));
    }

    @Test
    @DisplayName("REQ-PF-010 the CaptureFunds command carries payer, payee, amount and currency of the row")
    void captureComesFromTheRow() {
        Payment payment = payment(PaymentState.RISK_CHECKING);

        Transition t =
                SagaStateMachine.on(payment, new RiskApproved(payment.id()), 1).orElseThrow();

        assertThat(t.to()).isEqualTo(PaymentState.CAPTURING);
        assertThat(t.command()).contains(new CaptureFunds(payment.id(), "alice", "bob", 2_500, "INR"));
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
    @DisplayName("REQ-PF-010 a successful step carries no failure reason")
    void successHasNoReason() {
        Payment payment = payment(PaymentState.RESERVING);

        Transition t =
                SagaStateMachine.on(payment, new FundsReserved(payment.id()), 1).orElseThrow();

        assertThat(t.reason()).isNull();
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
    @DisplayName("REQ-PF-010 exactly COMPLETED, REFUNDED and REJECTED are terminal")
    void terminalStates() {
        List<PaymentState> terminal =
                Stream.of(PaymentState.values()).filter(PaymentState::terminal).toList();

        assertThat(terminal)
                .containsExactlyInAnyOrder(PaymentState.COMPLETED, PaymentState.REFUNDED, PaymentState.REJECTED);
    }
}
