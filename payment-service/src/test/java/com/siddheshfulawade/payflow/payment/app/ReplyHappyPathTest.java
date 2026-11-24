package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;

/** The saga's happy path and the wallet's rejection, driven by replies through the idempotent consumer. */
class ReplyHappyPathTest extends PaymentIntegrationTest {

    @Test
    @DisplayName("REQ-PF-010 FundsReserved moves RESERVING to RISK_CHECKING and queues CheckRisk built from the row")
    void reservedStartsTheRiskCheck() {
        Payment p = newPayment(new NewPayment("carol", "dave", 1_234, "INR"));
        Instant before = Instant.now();

        deliver(reply(new FundsReserved(p.id())));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(stepAttempts(p.id())).as("a new step starts at attempt 1").isEqualTo(1);
        assertThat(version(p.id())).isEqualTo(2);
        Envelope command = onlyOutbox(p.id(), CheckRisk.class);
        assertThat(command.payload()).isEqualTo(new CheckRisk(p.id(), "carol", 1_234, "INR"));
        assertThat(command.attempt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select topic from outbox where message_key = ? and payload like '%CheckRisk%'",
                        String.class, p.id().toString()))
                .isEqualTo("risk.commands");
        assertDeadline(p.id(), Duration.ofSeconds(5), before, Instant.now());
    }

    @Test
    @DisplayName(
            "REQ-PF-010 RiskApproved moves RISK_CHECKING to CAPTURING and queues CaptureFunds with payer and payee")
    void approvedStartsTheCapture() {
        Payment p = newPayment(new NewPayment("carol", "dave", 1_234, "INR"));
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        Instant before = Instant.now();

        deliver(reply(new RiskApproved(p.id())));

        assertThat(state(p.id())).isEqualTo("CAPTURING");
        assertThat(version(p.id())).isEqualTo(3);
        Envelope command = onlyOutbox(p.id(), CaptureFunds.class);
        assertThat(command.payload()).isEqualTo(new CaptureFunds(p.id(), "carol", "dave", 1_234, "INR"));
        assertThat(command.attempt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select topic from outbox where message_key = ? and payload like '%CaptureFunds%'",
                        String.class, p.id().toString()))
                .isEqualTo("wallet.commands");
        assertDeadline(p.id(), Duration.ofSeconds(10), before, Instant.now());
    }

    @Test
    @DisplayName("REQ-PF-010 FundsCaptured completes the payment: no deadline, no further command")
    void capturedCompletes() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.CAPTURING);
        long commandsBefore = outboxCount(p.id());

        deliver(reply(new FundsCaptured(p.id())));

        assertThat(state(p.id())).isEqualTo("COMPLETED");
        assertThat(deadline(p.id())).as("terminal states have no deadline").isNull();
        assertThat(version(p.id())).isEqualTo(4);
        assertThat(outboxCount(p.id())).as("nothing after the last step").isEqualTo(commandsBefore);
        assertThat(outboxCount(p.id())).isEqualTo(3);
    }

    @Test
    @DisplayName("REQ-PF-010 the audit trail of a completed payment has every transition once")
    void completedPaymentHasTheFullAuditTrail() {
        Payment p = newPayment();

        advanceTo(p.id(), PaymentState.COMPLETED);

        assertThat(events(p.id()))
                .containsExactlyInAnyOrder(
                        "null->RECEIVED",
                        "RECEIVED->RESERVING",
                        "RESERVING->RISK_CHECKING",
                        "RISK_CHECKING->CAPTURING",
                        "CAPTURING->COMPLETED");
    }

    @Test
    @DisplayName("REQ-PF-010 a transition event records the reply's message id and attempt")
    void eventRecordsTheTriggeringMessage() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));

        deliver(reserved);

        assertThat(jdbc.queryForMap(
                        "select message_id, attempt, reason from payment_event"
                                + " where payment_id = ? and to_state = 'RISK_CHECKING'",
                        p.id()))
                .containsEntry("message_id", reserved.messageId())
                .containsEntry("attempt", 1)
                .containsEntry("reason", null);
    }

    @Test
    @DisplayName("REQ-PF-010 FundsRejected ends REJECTED with the reason, no deadline and no further command")
    void rejectedEndsTheSaga() {
        Payment p = newPayment();
        Envelope rejection = reply(new FundsRejected(p.id(), "INSUFFICIENT_FUNDS"));

        deliver(rejection);

        assertThat(state(p.id())).isEqualTo("REJECTED");
        assertThat(deadline(p.id())).isNull();
        assertThat(outboxCount(p.id())).as("only the ReserveFunds").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select reason from payment_event where payment_id = ? and to_state = 'REJECTED'",
                        String.class,
                        p.id()))
                .contains("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject(
                        "select message_id from payment_event where payment_id = ? and to_state = 'REJECTED'",
                        UUID.class,
                        p.id()))
                .isEqualTo(rejection.messageId());
    }

    @Test
    @DisplayName(
            "REQ-PF-021 the handler never stores a reply on the inbox row, so a duplicate cannot re-send a command")
    void noStoredReplyOnTheInboxRow() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));

        deliver(reserved);

        assertThat(count(
                        "select count(*) from inbox where message_id = ? and reply_outbox_id is null",
                        reserved.messageId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 a redelivered reply (same message id) adds no outbox row and no event")
    void redeliveredReplyHasNoSecondEffect() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));
        deliver(reserved);
        Snapshot afterFirst = snapshotAll();

        IdempotentConsumer.Outcome second = deliver(reserved);

        assertThat(second).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        assertThat(snapshotAll())
                .as("no second CheckRisk, no event, no state change")
                .isEqualTo(afterFirst);
        assertThat(outboxCount(p.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-021 a redelivered reply at every step still holds the saga to one command per step")
    void everyStepSurvivesRedelivery() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));
        Envelope approved = reply(new RiskApproved(p.id()));
        Envelope captured = reply(new FundsCaptured(p.id()));

        for (Envelope step : new Envelope[] {reserved, reserved, approved, approved, captured, captured}) {
            deliver(step);
        }

        assertThat(state(p.id())).isEqualTo("COMPLETED");
        assertThat(outboxCount(p.id())).isEqualTo(3);
        assertThat(eventCount(p.id())).isEqualTo(5);
        assertThat(version(p.id())).isEqualTo(4);
    }

    @Test
    @DisplayName("REQ-PF-021 the handler needs the consumer's transaction: called alone it refuses and writes nothing")
    void handlerRequiresATransaction() {
        Payment p = newPayment();
        Snapshot before = snapshotAll();

        assertThatThrownBy(() -> handler.handle(reply(new FundsReserved(p.id()))))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(snapshotAll()).isEqualTo(before);
    }

    @Test
    @DisplayName("REQ-PF-010 the ReserveFunds of creation is still the only message while the first reply is awaited")
    void noExtraMessagesBeforeTheFirstReply() {
        Payment p = newPayment();

        assertThat(outbox(p.id()))
                .singleElement()
                .satisfies(e -> assertThat(e.payload()).isEqualTo(new ReserveFunds(p.id(), "alice", 500, "INR")));
    }
}
