package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** The sweeper in virtual time: overdue steps are re-sent with backoff, then compensated (REQ-PF-012, 013). */
class PaymentSweeperTest extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void virtualTimeAndLogs() {
        clock.set(T0);
        serviceLogger = (Logger) LoggerFactory.getLogger("com.siddheshfulawade.payflow.payment");
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    private void passDeadlines() {
        clock.advance(Duration.ofSeconds(31)); // longer than the cap, so every step is overdue
    }

    // ---- re-send ----

    @Test
    @DisplayName("REQ-PF-012 an overdue step is re-sent: same payload, new message id, attempt + 1, no event, backoff")
    void overdueStepIsResent() {
        Payment p = newPayment(new NewPayment("carol", "dave", 1_234, "INR"));
        Envelope original = onlyOutbox(p.id(), ReserveFunds.class);
        long versionBefore = version(p.id());
        List<String> eventsBefore = events(p.id());
        clock.advance(Duration.ofSeconds(11)); // the reserve deadline is 10 s

        int handled = sweeper.sweep();

        assertThat(handled).isEqualTo(1);
        assertThat(state(p.id())).isEqualTo("RESERVING");
        assertThat(stepAttempts(p.id())).isEqualTo(2);
        assertThat(version(p.id())).isEqualTo(versionBefore + 1);
        assertThat(events(p.id())).as("a re-send is not a transition").isEqualTo(eventsBefore);
        List<Envelope> sent = commands(p.id(), ReserveFunds.class);
        assertThat(sent).hasSize(2);
        Envelope resent = sent.get(1);
        assertThat(resent.payload()).isEqualTo(original.payload());
        assertThat(resent.messageId()).isNotEqualTo(original.messageId());
        assertThat(resent.attempt()).isEqualTo(2);
        assertThat(deadline(p.id()))
                .as("backoff: 10 s x 2 after the second attempt starts")
                .isEqualTo(T0.plusSeconds(11).plusSeconds(20));
        assertThat(jdbc.queryForList(
                        "select distinct topic from outbox where message_key = ?", String.class, p.id().toString()))
                .containsExactly(Topics.WALLET_COMMANDS);
    }

    @Test
    @DisplayName("REQ-PF-012 a step is overdue strictly after its deadline: at the deadline itself nothing happens")
    void exactlyAtTheDeadlineIsNotOverdue() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(10));
        Snapshot before = snapshotAll();

        assertThat(sweeper.sweep()).isZero();
        assertThat(snapshotAll()).isEqualTo(before);

        clock.advance(Duration.ofNanos(1_000)); // one microsecond, the resolution of the column
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(stepAttempts(p.id())).isEqualTo(2);
    }

    static Stream<Arguments> steps() {
        return Stream.of(
                Arguments.of(PaymentState.RESERVING, ReserveFunds.class, 10),
                Arguments.of(PaymentState.RISK_CHECKING, CheckRisk.class, 5),
                Arguments.of(PaymentState.CAPTURING, CaptureFunds.class, 10));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("steps")
    @DisplayName("REQ-PF-012 each step: 3 re-sends with doubling deadlines up to 30 s, then ReleaseFunds (TIMEOUT)")
    void threeResendsThenCompensation(PaymentState state, Class<?> commandType, int baseSeconds) {
        Payment p = newPayment();
        advanceTo(p.id(), state);
        Object original = commands(p.id(), commandType).get(0).payload();
        List<String> eventsBefore = events(p.id());

        for (int attempt = 2; attempt <= 4; attempt++) {
            passDeadlines();
            assertThat(sweeper.sweep()).as("sweep to attempt %d", attempt).isEqualTo(1);

            assertThat(state(p.id())).isEqualTo(state.name());
            assertThat(stepAttempts(p.id())).isEqualTo(attempt);
            List<Envelope> sent = commands(p.id(), commandType);
            assertThat(sent).hasSize(attempt);
            assertThat(sent.get(attempt - 1).attempt()).isEqualTo(attempt);
            assertThat(sent.get(attempt - 1).payload()).isEqualTo(original);
            long seconds = Math.min(baseSeconds * (1L << (attempt - 1)), 30);
            assertThat(deadline(p.id())).isEqualTo(clock.instant().plusSeconds(seconds));
        }
        assertThat(events(p.id())).as("re-sends write no events").isEqualTo(eventsBefore);
        assertThat(commands(p.id(), ReleaseFunds.class)).isEmpty();

        passDeadlines();
        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(stepAttempts(p.id())).as("the release is a new step").isEqualTo(1);
        Envelope release = onlyOutbox(p.id(), ReleaseFunds.class);
        assertThat(release.payload()).isEqualTo(new ReleaseFunds(p.id(), "alice", 500));
        assertThat(release.attempt()).isEqualTo(1);
        assertThat(commands(p.id(), commandType)).as("no fifth send").hasSize(4);
        assertThat(deadline(p.id())).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(eventReason(p.id(), "RELEASING")).isEqualTo("TIMEOUT");
        assertThat(eventMessageId(p.id(), "RELEASING")).as("no reply caused it").isNull();
        assertThat(jdbc.queryForObject(
                        "select attempt from payment_event where payment_id = ? and to_state = 'RELEASING'",
                        Integer.class,
                        p.id()))
                .isNull();
    }

    // ---- the release is retried until the wallet answers ----

    @Test
    @DisplayName("REQ-PF-012 a release is re-sent beyond 3 times, with capped backoff, and never ends by itself")
    void releaseIsRetriedForever() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RELEASING);
        Object original = onlyOutbox(p.id(), ReleaseFunds.class).payload();
        List<String> eventsBefore = events(p.id());

        for (int attempt = 2; attempt <= 8; attempt++) {
            passDeadlines();
            assertThat(sweeper.sweep()).isEqualTo(1);

            assertThat(state(p.id())).isEqualTo("RELEASING");
            assertThat(stepAttempts(p.id())).isEqualTo(attempt);
            assertThat(deadline(p.id()))
                    .isEqualTo(clock.instant().plusSeconds(Math.min(10L * (1L << (attempt - 1)), 30)));
        }

        List<Envelope> releases = commands(p.id(), ReleaseFunds.class);
        assertThat(releases).hasSize(8);
        assertThat(releases).allSatisfy(e -> assertThat(e.payload()).isEqualTo(original));
        assertThat(releases.stream().map(Envelope::messageId).distinct()).hasSize(8);
        assertThat(events(p.id())).isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("REQ-PF-012 a release past attempt 3 logs a warning, without account values")
    void lateReleaseWarns() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RELEASING);
        for (int attempt = 2; attempt <= 3; attempt++) {
            passDeadlines();
            sweeper.sweep();
        }
        assertThat(warnings()).as("no warning up to attempt 3").isEmpty();

        for (int attempt = 4; attempt <= 6; attempt++) {
            passDeadlines();
            sweeper.sweep();
        }

        assertThat(warnings()).isNotEmpty();
        assertThat(warnings())
                .allSatisfy(text -> assertThat(text).doesNotContain("alice").doesNotContain("bob"));
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("REQ-PF-011 the wallet's FundsReleased on a late attempt ends the retried release (REVERSED)")
    void retriedReleaseEndsWithTheWalletsAnswer() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RELEASING);
        for (int attempt = 2; attempt <= 7; attempt++) {
            passDeadlines();
            sweeper.sweep();
        }

        deliver(reply(new FundsReleased(p.id()), 1));
        assertThat(state(p.id())).as("the answer to attempt 1 is old").isEqualTo("RELEASING");
        deliver(reply(new FundsReleased(p.id()), 7));

        assertThat(state(p.id())).isEqualTo("REVERSED");
        assertThat(deadline(p.id())).isNull();
    }

    // ---- late replies after re-sends ----

    @Test
    @DisplayName("REQ-PF-013 after a re-send the reply of attempt 1 is ignored and the reply of attempt 2 is accepted")
    void oldAttemptIgnoredNewAttemptAccepted() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(11));
        sweeper.sweep();
        long version = version(p.id());

        deliver(reply(new FundsReserved(p.id()), 1));

        assertThat(state(p.id())).isEqualTo("RESERVING");
        assertThat(version(p.id())).isEqualTo(version);
        assertThat(commands(p.id(), CheckRisk.class)).isEmpty();

        deliver(reply(new FundsReserved(p.id()), 2));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(commands(p.id(), CheckRisk.class))
                .singleElement()
                .satisfies(e -> assertThat(e.attempt()).isEqualTo(1));
        assertThat(stepAttempts(p.id())).as("the new step starts at 1").isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-013 once compensating, every late reply of the earlier step is ignored until the release ends")
    void lateRepliesAfterTimeoutCompensationAreIgnored() {
        Payment p = newPayment();
        for (int i = 0; i < 4; i++) {
            passDeadlines();
            sweeper.sweep();
        }
        assertThat(state(p.id())).isEqualTo("RELEASING");
        long version = version(p.id());

        for (int attempt = 1; attempt <= 4; attempt++) {
            deliver(reply(new FundsReserved(p.id()), attempt));
        }

        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(version(p.id())).isEqualTo(version);
        assertThat(commands(p.id(), CheckRisk.class)).isEmpty();

        deliver(reply(new FundsReleased(p.id()), 1));
        assertThat(state(p.id())).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("REQ-PF-012 a RISK_CHECKING row whose reply was dead-lettered is recovered by the re-send")
    void deadLetteredReplyIsRecoveredByTheSweeper() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        // the RiskApproved never reached the handler: nothing is delivered
        clock.advance(Duration.ofSeconds(6)); // the risk deadline is 5 s

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(commands(p.id(), CheckRisk.class)).hasSize(2);
        assertThat(commands(p.id(), CheckRisk.class).get(1).attempt()).isEqualTo(2);
        deliver(reply(new RiskApproved(p.id()), 2));
        assertThat(state(p.id())).isEqualTo("CAPTURING");
        deliver(reply(new FundsCaptured(p.id()), 1));
        assertThat(state(p.id())).isEqualTo("COMPLETED");
    }

    // ---- what the sweeper must not touch ----

    @Test
    @DisplayName("REQ-PF-012 steps that are not overdue and finished payments are left alone")
    void notOverdueAndTerminalRowsAreUntouched() {
        Payment completed = newPayment();
        advanceTo(completed.id(), PaymentState.COMPLETED);
        Payment rejected = newPayment();
        advanceTo(rejected.id(), PaymentState.REJECTED);
        Payment reversed = newPayment();
        advanceTo(reversed.id(), PaymentState.REVERSED);
        clock.advance(Duration.ofSeconds(1_000));
        Payment fresh = newPayment(); // its deadline lies 10 s after the new now
        Snapshot before = snapshotAll();

        assertThat(sweeper.sweep()).isZero();

        assertThat(snapshotAll()).isEqualTo(before);
        assertThat(stepAttempts(fresh.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-012 at most 50 payments per sweep, the longest overdue first")
    void batchOfFiftyOldestFirst() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add(newPayment().id());
            clock.advance(Duration.ofSeconds(1));
        }
        clock.advance(Duration.ofSeconds(100)); // all 60 are overdue

        assertThat(sweeper.sweep()).isEqualTo(50);

        for (int i = 0; i < 60; i++) {
            assertThat(stepAttempts(ids.get(i)))
                    .as("payment %d (created %d s after the first)", i, i)
                    .isEqualTo(i < 50 ? 2 : 1);
        }
        assertThat(sweeper.sweep()).isEqualTo(10);
    }
}
