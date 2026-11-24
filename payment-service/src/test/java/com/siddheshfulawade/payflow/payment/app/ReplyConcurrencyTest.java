package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Two handlers (two instances, or a redelivery racing a re-send) never both advance one payment (REQ-PF-014). */
class ReplyConcurrencyTest extends PaymentIntegrationTest {

    private static boolean allowed(Object outcome) {
        return outcome instanceof IdempotentConsumer.Outcome || outcome instanceof StaleVersionException;
    }

    /** A handler that lost the race is redelivered, as Kafka does after the exception. */
    private void redeliverFailures(List<Envelope> sent, List<Object> outcomes) {
        for (int i = 0; i < sent.size(); i++) {
            if (outcomes.get(i) instanceof Throwable) {
                deliver(sent.get(i));
            }
        }
    }

    @Test
    @DisplayName("REQ-PF-014 two copies of one reply with different message ids make one transition and one command")
    void twoCopiesAdvanceOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Payment p = newPayment();
            Envelope first = reply(new FundsReserved(p.id()));
            List<Envelope> copies = List.of(first, resent(first));
            List<Callable<IdempotentConsumer.Outcome>> tasks = new ArrayList<>();
            copies.forEach(copy -> tasks.add(() -> deliver(copy)));

            List<Object> outcomes = concurrently(tasks, TIMEOUT);
            redeliverFailures(copies, outcomes);

            assertThat(outcomes)
                    .allSatisfy(o -> assertThat(allowed(o)).as("outcome %s", o).isTrue());
            assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
            assertThat(version(p.id()))
                    .as("exactly one transition after creation")
                    .isEqualTo(2);
            assertThat(events(p.id()).stream().filter("RESERVING->RISK_CHECKING"::equals))
                    .hasSize(1);
            assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CheckRisk))
                    .as("exactly one next command")
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("REQ-PF-021 ten deliveries of the very same message make one effect and nine duplicates")
    void sameMessageTenTimes() throws Exception {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));
        List<Callable<IdempotentConsumer.Outcome>> tasks = new ArrayList<>();
        IntStream.range(0, 10).forEach(i -> tasks.add(() -> deliver(reserved)));

        List<Object> outcomes = concurrently(tasks, TIMEOUT);

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(IdempotentConsumer.Outcome.class));
        assertThat(outcomes.stream().filter(IdempotentConsumer.Outcome.APPLIED::equals))
                .hasSize(1);
        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(version(p.id())).isEqualTo(2);
        assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CheckRisk))
                .hasSize(1);
    }

    @Test
    @DisplayName("REQ-PF-014 FundsReserved and FundsRejected racing for one payment: exactly one wins, never both")
    void conflictingRepliesOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            Payment p = newPayment();
            List<Envelope> replies =
                    List.of(reply(new FundsReserved(p.id())), reply(new FundsRejected(p.id(), "INSUFFICIENT_FUNDS")));
            List<Callable<IdempotentConsumer.Outcome>> tasks = new ArrayList<>();
            replies.forEach(r -> tasks.add(() -> deliver(r)));

            List<Object> outcomes = concurrently(tasks, TIMEOUT);
            redeliverFailures(replies, outcomes);

            assertThat(outcomes)
                    .allSatisfy(o -> assertThat(allowed(o)).as("outcome %s", o).isTrue());
            String finalState = state(p.id());
            assertThat(finalState).isIn("RISK_CHECKING", "REJECTED");
            assertThat(version(p.id())).isEqualTo(2);
            long checks = outbox(p.id()).stream()
                    .filter(e -> e.payload() instanceof CheckRisk)
                    .count();
            assertThat(checks)
                    .as("CheckRisk only when the payment went on")
                    .isEqualTo(finalState.equals("REJECTED") ? 0 : 1);
            assertThat(events(p.id())).hasSize(3);
        }
    }
}
