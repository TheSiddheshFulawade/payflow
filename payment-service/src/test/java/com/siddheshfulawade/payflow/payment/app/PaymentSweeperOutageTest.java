package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A broker outage (or a crash between the database commit and the publish) leaves the command unsent in the outbox. The
 * sweeper must not pile up more commands behind it: it postpones the deadline instead (REQ-PF-012, 020). The relay is
 * switched off in these tests, so an unsent row is the normal state; {@code relayAll()} plays "the broker is back".
 */
class PaymentSweeperOutageTest extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    @BeforeEach
    void virtualTime() {
        clock.set(T0);
    }

    private void passDeadlines() {
        clock.advance(Duration.ofSeconds(31)); // longer than the cap, so every step is overdue
    }

    @Test
    @DisplayName(
            "REQ-PF-012 five sweeps during a broker outage leave one outbox row on attempt 1: the deadline is postponed")
    void outageDoesNotPileUpCommands() {
        Payment p = newPayment();
        long version = version(p.id());
        List<String> events = events(p.id());

        for (int sweep = 1; sweep <= 5; sweep++) {
            clock.advance(Duration.ofSeconds(11)); // the reserve deadline is 10 s
            sweeper.sweep();

            assertThat(version(p.id()))
                    .as("sweep %d postponed the deadline", sweep)
                    .isEqualTo(version + sweep);
            assertThat(deadline(p.id()))
                    .as("new deadline = now + the current attempt's wait")
                    .isEqualTo(clock.instant().plusSeconds(10));
        }

        assertThat(state(p.id())).isEqualTo("RESERVING");
        assertThat(stepAttempts(p.id())).as("a postponement is not an attempt").isEqualTo(1);
        assertThat(outboxCount(p.id())).as("no new command").isEqualTo(1);
        assertThat(events(p.id())).as("no event").isEqualTo(events);
    }

    @Test
    @DisplayName(
            "REQ-PF-012 a postponed payment is not overdue before its new deadline: the next sweep leaves it alone")
    void postponedPaymentWaitsForItsNewDeadline() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(11));
        sweeper.sweep();
        long version = version(p.id());

        clock.advance(Duration.ofSeconds(9)); // 9 s of the new 10 s
        sweeper.sweep();

        assertThat(version(p.id())).isEqualTo(version);
        assertThat(outboxCount(p.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-012 once the command is published, the next overdue sweep re-sends it normally")
    void publishedCommandIsResentWhenOverdueAgain() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(11));
        sweeper.sweep(); // outage: postponed
        assertThat(stepAttempts(p.id())).isEqualTo(1);

        relayAll(); // the broker is back and the relay published the row
        clock.advance(Duration.ofSeconds(11)); // no reply came
        sweeper.sweep();

        assertThat(stepAttempts(p.id())).isEqualTo(2);
        assertThat(commands(p.id(), ReserveFunds.class)).hasSize(2);
        assertThat(deadline(p.id()))
                .as("backoff goes on: 10 s x 2 after the second attempt starts")
                .isEqualTo(clock.instant().plusSeconds(20));
    }

    @Test
    @DisplayName("REQ-PF-012 the wait is that of the CURRENT attempt: attempt 2 postpones by 20 s")
    void postponementUsesTheDeadlineOfTheCurrentAttempt() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(11));
        sweepRelayed(); // attempt 2, its command is now unsent again
        assertThat(stepAttempts(p.id())).isEqualTo(2);
        long version = version(p.id());

        clock.advance(Duration.ofSeconds(21));
        sweeper.sweep();

        assertThat(stepAttempts(p.id())).isEqualTo(2);
        assertThat(version(p.id())).isEqualTo(version + 1);
        assertThat(deadline(p.id())).isEqualTo(clock.instant().plusSeconds(20));
        assertThat(commands(p.id(), ReserveFunds.class)).hasSize(2);
    }

    @Test
    @DisplayName("REQ-PF-012 an unpublished command at attempt 4 is not compensated: the release waits for the broker")
    void unsentCommandAtTheLastAttemptIsNotCompensated() {
        Payment p = newPayment();
        for (int attempt = 2; attempt <= 4; attempt++) {
            passDeadlines();
            sweepRelayed();
        }
        assertThat(stepAttempts(p.id())).isEqualTo(4);
        long version = version(p.id());

        passDeadlines();
        sweeper.sweep(); // outage: the attempt 4 command never left the outbox

        assertThat(state(p.id())).as("not compensated").isEqualTo("RESERVING");
        assertThat(stepAttempts(p.id())).isEqualTo(4);
        assertThat(commands(p.id(), ReleaseFunds.class)).isEmpty();
        assertThat(commands(p.id(), ReserveFunds.class)).hasSize(4);
        assertThat(version(p.id())).isEqualTo(version + 1);
        assertThat(deadline(p.id()))
                .as("30 s, the cap")
                .isEqualTo(clock.instant().plusSeconds(30));

        relayAll();
        passDeadlines();
        sweeper.sweep();

        assertThat(state(p.id()))
                .as("published, unanswered: now it is compensated")
                .isEqualTo("RELEASING");
        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(1);
    }

    @Test
    @DisplayName("REQ-PF-012 an unpublished ReleaseFunds is postponed too: five sweeps, one release, attempt 1")
    void unsentReleaseIsPostponed() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RELEASING);
        long version = version(p.id());

        for (int sweep = 1; sweep <= 5; sweep++) {
            clock.advance(Duration.ofSeconds(11));
            sweeper.sweep();
        }

        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(stepAttempts(p.id())).isEqualTo(1);
        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(1);
        assertThat(version(p.id())).isEqualTo(version + 5);

        relayAll();
        clock.advance(Duration.ofSeconds(11));
        sweeper.sweep();

        assertThat(stepAttempts(p.id())).as("published and unanswered: re-sent").isEqualTo(2);
        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(2);
    }

    @Test
    @DisplayName("REQ-PF-012 only the payment's own unpublished command postpones it: another payment is re-sent")
    void anotherPaymentsUnsentRowDoesNotMatter() {
        Payment published = newPayment();
        relayAll();
        Payment unpublished = newPayment();
        clock.advance(Duration.ofSeconds(11));

        sweeper.sweep();

        assertThat(stepAttempts(published.id())).isEqualTo(2);
        assertThat(stepAttempts(unpublished.id())).isEqualTo(1);
        assertThat(unsentCommands(unpublished.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-020 a reply to the postponed step still moves the payment (the sweeper holds nothing back)")
    void replyAfterPostponementIsAccepted() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(11));
        sweeper.sweep();

        deliver(reply(new FundsReserved(p.id()), 1));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
    }
}
