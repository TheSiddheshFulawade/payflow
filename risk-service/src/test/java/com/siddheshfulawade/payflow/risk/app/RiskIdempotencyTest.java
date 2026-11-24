package com.siddheshfulawade.payflow.risk.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.risk.support.RiskIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Redeliveries, re-sends, contradictions, failures between the count and the commit, and the UTC date. */
class RiskIdempotencyTest extends RiskIntegrationTest {

    private final UUID payment = UUID.randomUUID();
    private final String payer = newPayer();

    // ---- the same message again (inbox) ----

    @Test
    @DisplayName(
            "REQ-PF-021 a redelivered CheckRisk (same message id) has no second effect and re-sends the same reply")
    void redeliveredCheckRiskHasNoSecondEffect() {
        Envelope command = check(payment, payer, 1_000);
        Envelope first = apply(command);
        long outboxAfterFirst = outboxRows();
        long callsAfterFirst = velocity.callsFor(payer);

        consumer.handle(command, handler::handle);

        assertThat(inboxRows()).isEqualTo(1);
        assertThat(decisionRows(payment)).isEqualTo(1);
        assertThat(dailyTotal(payer)).as("counted once").isEqualTo(1_000);
        assertThat(velocity.callsFor(payer)).as("velocity not asked again").isEqualTo(callsAfterFirst);
        assertThat(outboxRows()).as("the stored reply is queued again").isEqualTo(outboxAfterFirst + 1);
        assertThat(jdbc.queryForList(
                        "select message_key from outbox where payload like ?",
                        String.class,
                        "%" + first.messageId() + "%"))
                .hasSize(2)
                .containsOnly(payment.toString());
    }

    // ---- the same payment again (new message id, e.g. the sweeper) ----

    @Test
    @DisplayName("REQ-PF-021 a re-sent CheckRisk (new message id) returns the stored approval with no effect")
    void resentApprovalIsAnsweredFromTheStoredDecision() {
        Envelope command = check(payment, payer, 1_000, 1);
        apply(command);
        long callsBefore = velocity.callsFor(payer);

        Envelope reply = apply(resentOnAttempt(command, 2));

        assertThat(reply.payload()).isEqualTo(new RiskApproved(payment));
        assertThat(reply.attempt())
                .as("the reply carries the re-sent command's attempt")
                .isEqualTo(2);
        assertThat(decisionRows(payment)).isEqualTo(1);
        assertThat(dailyTotal(payer)).as("counted once").isEqualTo(1_000);
        assertThat(velocity.callsFor(payer)).as("no second velocity check").isEqualTo(callsBefore);
    }

    @Test
    @DisplayName(
            "REQ-PF-021 a re-sent CheckRisk of a rejected payment returns the same rule, even if the world changed")
    void resentRejectionKeepsItsRule() {
        for (int i = 0; i < 5; i++) {
            pay(payer, 100);
        }
        Envelope command = check(payment, payer, 100);
        assertThat(apply(command).payload()).isEqualTo(new RiskRejected(payment, "VELOCITY"));

        // a minute later the window is empty again: a fresh check would approve, but the decision is permanent
        clock.set(Instant.now().plusSeconds(120));
        Envelope reply = apply(resent(command));

        assertThat(reply.payload()).isEqualTo(new RiskRejected(payment, "VELOCITY"));
        assertThat(dailyTotal(payer)).as("the rejected amount was never added").isEqualTo(500);
        assertThat(decisionRows(payment)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 an approved payment stays approved on a re-send after the payer reached the daily limit")
    void resentApprovalSurvivesLaterLimits() {
        Envelope command = check(payment, payer, 1_000);
        apply(command);
        for (int i = 0; i < 3; i++) {
            pay(payer, 5_000_000);
        }
        pay(payer, 4_999_000); // total 20,000,000 - nothing more fits

        Envelope reply = apply(resent(command));

        assertThat(reply.payload()).isEqualTo(new RiskApproved(payment));
        assertThat(dailyTotal(payer)).isEqualTo(20_000_000);
    }

    // ---- contradictions and strangers ----

    @Test
    @DisplayName("REQ-PF-021 a CheckRisk for a decided payment with another amount is poison: no values in the message")
    void differentAmountIsPoison() {
        apply(check(payment, payer, 1_000));

        assertPoison(check(payment, payer, 2_000), payer, "2000", "1000", payment.toString());

        assertThat(decision(payment)).containsEntry("amount", 1_000L);
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("REQ-PF-021 a CheckRisk for a decided payment with another payer is poison")
    void differentPayerIsPoison() {
        apply(check(payment, payer, 1_000));
        String other = newPayer();

        assertPoison(check(payment, other, 1_000), payer, other);

        assertThat(dailyTotal(other)).isZero();
        assertThat(velocity.callsFor(other)).isZero();
    }

    @Test
    @DisplayName("REQ-PF-021 a contradicting command for a REJECTED payment is poison too")
    void contradictionOfARejectionIsPoison() {
        apply(check(payment, payer, 6_000_000));

        assertPoison(check(payment, payer, 100), payer, "6000000", payment.toString());

        assertThat(decision(payment)).containsEntry("decision", "REJECTED");
    }

    @Test
    @DisplayName("REQ-PF-021 a message that is not a risk command is poison and leaves no trace")
    void foreignCommandIsPoison() {
        Envelope foreign = Envelope.of(new ReserveFunds(payment, payer, 100, "INR"), 1, Clock.systemUTC());

        assertPoison(foreign, payer);

        assertThat(decisionRows(payment)).isZero();
        assertThat(velocity.calls()).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-021 a reply sent to risk.commands is poison")
    void replyIsPoison() {
        Envelope reply = Envelope.of(new RiskApproved(payment), 1, Clock.systemUTC());

        assertPoison(reply, payment.toString());

        assertThat(decisionRows(payment)).isZero();
    }

    // ---- failures: fail closed, nothing half done ----

    @Test
    @DisplayName(
            "REQ-PF-032 when the velocity counter fails nothing is stored and nothing is approved; a retry approves")
    void velocityFailureRollsBackEverything() {
        Envelope command = check(payment, payer, 1_000);
        velocity.failBeforeCounting();

        assertThatThrownBy(() -> consumer.handle(command, handler::handle))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(PoisonMessageException.class);

        assertThat(inboxRows())
                .as("inbox rolled back: the retry is not a duplicate")
                .isZero();
        assertThat(decisionRows(payment)).as("no decision stored").isZero();
        assertThat(outboxRows()).as("no reply queued, certainly no approval").isZero();
        assertThat(dailyTotal(payer)).as("daily total rolled back").isZero();

        velocity.recover();
        Envelope reply = apply(command);

        assertThat(reply.payload()).isEqualTo(new RiskApproved(payment));
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
        assertThat(decisionRows(payment)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-032 a crash after the velocity count but before the commit costs no second slot on redelivery")
    void crashAfterCountingDoesNotCountTwice() {
        for (int i = 0; i < 4; i++) {
            pay(payer, 100); // 4 slots
        }
        Envelope command = check(payment, payer, 100);
        velocity.failAfterCounting();
        assertThatThrownBy(() -> consumer.handle(command, handler::handle)).isInstanceOf(RuntimeException.class);
        assertThat(decisionRows(payment)).as("rolled back").isZero();
        velocity.recover();

        Envelope redelivered = apply(command);
        Envelope sixth = pay(payer, 100);

        assertThat(redelivered.payload())
                .as("its slot was taken before the crash; asking again is free")
                .isEqualTo(new RiskApproved(payment));
        assertThat(sixth.payload())
                .as("the 5th slot was used once, so the 6th payment is refused")
                .isEqualTo(new RiskRejected(sixth.paymentId(), "VELOCITY"));
        assertThat(dailyTotal(payer)).as("4 + the redelivered one").isEqualTo(500);
    }

    // ---- the business date is UTC ----

    @Test
    @DisplayName("REQ-PF-031 the daily total restarts at midnight UTC")
    void dailyTotalRestartsAtUtcMidnight() {
        LocalDate day1 = LocalDate.of(2030, 3, 1);
        LocalDate day2 = LocalDate.of(2030, 3, 2);
        clock.set(Instant.parse("2030-03-01T23:59:59Z"));
        for (int i = 0; i < 4; i++) {
            pay(payer, 5_000_000);
        }
        UUID blocked = UUID.randomUUID();
        assertThat(apply(check(blocked, payer, 5_000_000)).payload())
                .as("the limit is reached on day 1")
                .isEqualTo(new RiskRejected(blocked, "DAILY_LIMIT"));

        clock.set(Instant.parse("2030-03-02T00:00:00Z"));
        Envelope nextDay = pay(payer, 5_000_000);

        assertThat(nextDay.payload()).isEqualTo(new RiskApproved(nextDay.paymentId()));
        assertThat(dailyTotal(payer, day1)).isEqualTo(20_000_000);
        assertThat(dailyTotal(payer, day2)).isEqualTo(5_000_000);
        assertThat(dailyTotalRows(payer)).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-031 the business date is the UTC date, not the machine's: 20:00Z is still the same UTC day")
    void businessDateIsUtc() {
        clock.set(Instant.parse("2030-03-01T20:00:00Z")); // already 2030-03-02 in IST, still 03-01 in UTC

        pay(payer, 1_000);

        assertThat(dailyTotal(payer, LocalDate.of(2030, 3, 1))).isEqualTo(1_000);
        assertThat(dailyTotalRows(payer)).isEqualTo(1);
    }
}
