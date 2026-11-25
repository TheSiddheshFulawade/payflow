package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Two instances (or two threads) sweeping at once: every overdue payment moves exactly once (REQ-PF-012, 014). */
class PaymentSweeperConcurrencyTest extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void virtualTime() {
        clock.set(T0);
    }

    private static Callable<Integer> sweepOn(PaymentSweeper sweeper) {
        return sweeper::sweep;
    }

    @Test
    @DisplayName("REQ-PF-014 three sweepers at once re-send every overdue payment exactly once (SKIP LOCKED)")
    void everyPaymentIsResentOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            jdbc.execute("truncate table outbox, inbox, payment_event, payment");
            clock.set(T0);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                ids.add(newPayment().id());
            }
            relayAll();
            clock.advance(Duration.ofSeconds(11));

            List<Object> results = concurrently(List.of(sweepOn(sweeper), sweepOn(sweeper), sweepOn(sweeper)), TIMEOUT);

            assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(Integer.class));
            assertThat(results.stream().mapToInt(r -> (Integer) r).sum())
                    .as("each payment handled by exactly one sweeper")
                    .isEqualTo(30);
            for (UUID id : ids) {
                assertThat(stepAttempts(id)).isEqualTo(2);
                assertThat(version(id)).isEqualTo(2);
                assertThat(commands(id, ReserveFunds.class))
                        .extracting(Envelope::attempt)
                        .containsExactly(1, 2);
            }
        }
    }

    @Test
    @DisplayName("REQ-PF-014 a row another transaction holds is skipped, not waited for, and handled by the next sweep")
    void lockedRowIsSkipped() throws Exception {
        Payment p = newPayment();
        relayAll();
        clock.advance(Duration.ofSeconds(11));

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var statement = other.prepareStatement("select id from payment where id = ? for update")) {
                statement.setObject(1, p.id());
                statement.executeQuery().close();

                long start = System.nanoTime();
                int handled = sweeper.sweep();
                Duration took = Duration.ofNanos(System.nanoTime() - start);

                assertThat(handled).isZero();
                assertThat(took).as("skipped, not blocked on the lock").isLessThan(Duration.ofSeconds(3));
                assertThat(stepAttempts(p.id())).isEqualTo(1);
            } finally {
                other.rollback();
            }
        }

        assertThat(sweepRelayed()).isEqualTo(1);
        assertThat(stepAttempts(p.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-014 the sweeper and a reply racing on one payment: exactly one wins, no command is doubled")
    void sweeperAndReplyRace() throws Exception {
        for (int round = 0; round < 15; round++) {
            Payment p = newPayment();
            relayAll();
            clock.advance(Duration.ofSeconds(11)); // overdue, and its reply is on the way
            Envelope reserved = reply(new FundsReserved(p.id()), 1);

            List<Object> outcomes = concurrently(
                    List.of(sweepOn(sweeper), (Callable<IdempotentConsumer.Outcome>) () -> deliver(reserved)), TIMEOUT);
            // the handler that lost is redelivered, as Kafka does after its exception
            if (outcomes.get(1) instanceof Throwable lost) {
                assertThat(lost).isInstanceOf(StaleVersionException.class);
                deliver(reserved);
            }

            assertThat(outcomes.get(0)).isInstanceOf(Integer.class);
            String state = state(p.id());
            if (state.equals("RISK_CHECKING")) {
                assertThat(stepAttempts(p.id())).as("the reply won").isEqualTo(1);
                assertThat(commands(p.id(), ReserveFunds.class)).hasSize(1);
            } else {
                assertThat(state).as("the sweeper won").isEqualTo("RESERVING");
                assertThat(stepAttempts(p.id())).isEqualTo(2);
                assertThat(commands(p.id(), ReserveFunds.class)).hasSize(2);
                assertThat(commands(p.id(), CheckRisk.class))
                        .as("the attempt 1 reply is old by now")
                        .isEmpty();
            }
            Set<String> sends = new HashSet<>();
            for (Envelope e : outbox(p.id())) {
                assertThat(sends.add(e.type() + "#" + e.attempt()))
                        .as("%s of attempt %d sent twice", e.type(), e.attempt())
                        .isTrue();
            }
            assertThat(version(p.id()))
                    .as("one move: the reply's or the re-send's")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName(
            "REQ-PF-014 a reply racing a postponing sweep: always RISK_CHECKING on attempt 1, one transition event")
    void replyAgainstPostponement() throws Exception {
        for (int round = 0; round < 15; round++) {
            Payment p = newPayment(); // its ReserveFunds is unsent (no relayAll): the sweeper postpones
            clock.advance(Duration.ofSeconds(11));
            Envelope reserved = reply(new FundsReserved(p.id()), 1);

            List<Object> outcomes = concurrently(
                    List.of(sweepOn(sweeper), (Callable<IdempotentConsumer.Outcome>) () -> deliver(reserved)), TIMEOUT);
            if (outcomes.get(1) instanceof Throwable lost) {
                assertThat(lost).isInstanceOf(StaleVersionException.class);
                deliver(reserved); // redelivered, as Kafka does
            }

            assertThat(outcomes.get(0)).isInstanceOf(Integer.class);
            assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
            assertThat(stepAttempts(p.id()))
                    .as("a postponement is not an attempt")
                    .isEqualTo(1);
            assertThat(events(p.id()).stream().filter("RESERVING->RISK_CHECKING"::equals))
                    .as("the reply's transition is audited once")
                    .hasSize(1);
            assertThat(commands(p.id(), ReserveFunds.class)).hasSize(1);
            assertThat(commands(p.id(), CheckRisk.class)).hasSize(1);
        }
    }

    @Test
    @DisplayName(
            "REQ-PF-014 the relay marking the command sent racing a sweep: one postponement or one re-send, never both")
    void relayAgainstSweep() throws Exception {
        for (int round = 0; round < 15; round++) {
            Payment p = newPayment();
            clock.advance(Duration.ofSeconds(11));
            long versionBefore = version(p.id());

            concurrently(
                    List.<Callable<?>>of(sweepOn(sweeper), () -> {
                        relayAll();
                        return 0;
                    }),
                    TIMEOUT);

            assertThat(version(p.id())).as("exactly one move").isEqualTo(versionBefore + 1);
            int attempts = stepAttempts(p.id());
            assertThat(attempts).isIn(1, 2);
            assertThat(commands(p.id(), ReserveFunds.class))
                    .as("attempt %d: a postponement adds no command, a re-send adds one", attempts)
                    .hasSize(attempts);
            assertThat(state(p.id())).isEqualTo("RESERVING");
        }
    }
}
