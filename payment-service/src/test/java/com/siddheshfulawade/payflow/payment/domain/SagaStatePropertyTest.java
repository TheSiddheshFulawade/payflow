package com.siddheshfulawade.payflow.payment.domain;

import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.NOW;
import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Random sequences of happy-path replies, failures, duplicates and wrong-attempt replies for one payment. A code
 * 0..7 is a reply kind (code % 4) and an attempt (code / 4 + 1); every step runs on attempt 1, so attempt 2 is
 * always a stale or foreign reply.
 */
class SagaStatePropertyTest {

    private static final Set<String> LEGAL_EDGES = Set.of(
            "RESERVING->RISK_CHECKING", "RESERVING->REJECTED", "RISK_CHECKING->CAPTURING", "CAPTURING->COMPLETED");

    private static Reply reply(Payment payment, int code) {
        return switch (code % 4) {
            case 0 -> new FundsReserved(payment.id());
            case 1 -> new RiskApproved(payment.id());
            case 2 -> new FundsCaptured(payment.id());
            default -> new FundsRejected(payment.id(), "INSUFFICIENT_FUNDS");
        };
    }

    /** What the persistence layer does with a transition: new state, new step on attempt 1, version + 1. */
    private static Payment applied(Payment p, Transition t) {
        return new Payment(
                p.id(),
                p.clientId(),
                p.idempotencyKey(),
                p.requestHash(),
                p.payer(),
                p.payee(),
                p.amount(),
                p.currency(),
                t.to(),
                1,
                t.to().terminal() ? null : NOW.plusSeconds(10),
                p.version() + 1,
                p.createdAt(),
                NOW);
    }

    private static Payment started() {
        Payment received = payment(PaymentState.RECEIVED);
        return applied(received, SagaStateMachine.start(received));
    }

    // REQ-PF-010 a payment reaches at most one terminal state and never leaves it
    @Property
    void terminalAtMostOnceAndNeverLeft(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 7) Integer> codes) {
        Payment p = started();
        int terminalEntries = 0;

        for (int code : codes) {
            boolean wasTerminal = p.state().terminal();
            Optional<Transition> t = SagaStateMachine.on(p, reply(p, code), code / 4 + 1);
            if (wasTerminal) {
                assertThat(t).as("a terminal payment ignores everything").isEmpty();
            }
            if (t.isPresent()) {
                p = applied(p, t.get());
                if (p.state().terminal()) {
                    terminalEntries++;
                }
            }
        }

        assertThat(terminalEntries).isLessThanOrEqualTo(1);
    }

    // REQ-PF-010 every move follows an edge of the saga and carries a command exactly when the payment goes on
    @Property
    void onlyLegalEdgesAndOneCommandPerStep(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 7) Integer> codes) {
        Payment p = started();

        for (int code : codes) {
            Optional<Transition> t = SagaStateMachine.on(p, reply(p, code), code / 4 + 1);
            if (t.isEmpty()) {
                continue;
            }
            assertThat(LEGAL_EDGES).contains(p.state() + "->" + t.get().to());
            assertThat(t.get().command().isPresent())
                    .as(
                            "%s -> %s sends a command only while the saga continues",
                            p.state(), t.get().to())
                    .isEqualTo(!t.get().to().terminal());
            p = applied(p, t.get());
        }
    }

    // REQ-PF-013 a reply of another attempt never moves the payment
    @Property
    void wrongAttemptNeverMoves(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 3) Integer> kinds) {
        Payment p = started();

        for (int kind : kinds) {
            Optional<Transition> t = SagaStateMachine.on(p, reply(p, kind), 2);

            assertThat(t).isEmpty();
        }
    }

    // REQ-PF-010 COMPLETED is only reachable through every step in order, whatever the duplicates in between
    @Property
    void completedOnlyThroughAllSteps(@ForAll @Size(max = 40) List<@IntRange(min = 0, max = 7) Integer> codes) {
        Payment p = started();
        List<PaymentState> path = new ArrayList<>(List.of(p.state()));

        for (int code : codes) {
            Optional<Transition> t = SagaStateMachine.on(p, reply(p, code), code / 4 + 1);
            if (t.isPresent()) {
                p = applied(p, t.get());
                path.add(p.state());
            }
        }

        if (p.state() == PaymentState.COMPLETED) {
            assertThat(path)
                    .containsExactly(
                            PaymentState.RESERVING,
                            PaymentState.RISK_CHECKING,
                            PaymentState.CAPTURING,
                            PaymentState.COMPLETED);
        }
    }
}
