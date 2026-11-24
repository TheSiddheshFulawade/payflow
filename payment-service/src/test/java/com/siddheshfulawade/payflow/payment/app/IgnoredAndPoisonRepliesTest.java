package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Late, duplicate and foreign replies change nothing; replies that cannot be right are poison. */
class IgnoredAndPoisonRepliesTest extends PaymentIntegrationTest {

    /**
     * An ignored reply is still recorded in the inbox (so its redelivery is a no-op too), and nothing else moves: not
     * the payment row (state, version, updated_at), not the events, not the outbox.
     */
    private void assertIgnored(Envelope late) {
        Snapshot before = snapshotAll();

        IdempotentConsumer.Outcome outcome = deliver(late);

        Snapshot after = snapshotAll();
        assertThat(outcome).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(after.payments()).as("payment row").isEqualTo(before.payments());
        assertThat(after.events()).as("events").isEqualTo(before.events());
        assertThat(after.outbox()).as("outbox").isEqualTo(before.outbox());
        assertThat(after.inbox()).as("inbox").isEqualTo(before.inbox() + 1);
        assertThat(count("select count(*) from inbox where message_id = ?", late.messageId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-013 a second FundsReserved (new message id) after RISK_CHECKING changes nothing")
    void duplicateReplyWithNewIdIsIgnored() {
        Payment p = newPayment();
        Envelope first = reply(new FundsReserved(p.id()));
        deliver(first);

        assertIgnored(resent(first));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(outboxCount(p.id())).as("ReserveFunds and one CheckRisk").isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-013 a late FundsReserved after COMPLETED is ignored")
    void lateReserveAfterCompletion() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.COMPLETED);

        assertIgnored(reply(new FundsReserved(p.id())));

        assertThat(state(p.id())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("REQ-PF-013 a late FundsRejected after COMPLETED does not turn it into REJECTED")
    void lateRejectionAfterCompletion() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.COMPLETED);

        assertIgnored(reply(new FundsRejected(p.id(), "INSUFFICIENT_FUNDS")));

        assertThat(state(p.id())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("REQ-PF-013 a late FundsRejected after RISK_CHECKING does not undo the reservation")
    void lateRejectionAfterReserve() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);

        assertIgnored(reply(new FundsRejected(p.id(), "INSUFFICIENT_FUNDS")));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
    }

    @Test
    @DisplayName("REQ-PF-013 a reply after REJECTED cannot bring the payment back")
    void replyAfterRejection() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.REJECTED);

        assertIgnored(reply(new FundsReserved(p.id())));
        assertIgnored(reply(new RiskApproved(p.id())));
        assertIgnored(reply(new FundsCaptured(p.id())));

        assertThat(state(p.id())).isEqualTo("REJECTED");
        assertThat(outboxCount(p.id())).as("only the ReserveFunds").isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-013 RiskApproved while RESERVING does not skip the reservation")
    void approvalBeforeReservation() {
        Payment p = newPayment();

        assertIgnored(reply(new RiskApproved(p.id())));

        assertThat(state(p.id())).isEqualTo("RESERVING");
    }

    @Test
    @DisplayName("REQ-PF-013 FundsCaptured before the risk check never completes the payment")
    void captureReplyTooEarly() {
        Payment p = newPayment();
        assertIgnored(reply(new FundsCaptured(p.id())));
        advanceTo(p.id(), PaymentState.RISK_CHECKING);

        assertIgnored(reply(new FundsCaptured(p.id())));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
    }

    @Test
    @DisplayName("REQ-PF-013 a reply of another attempt is ignored while the step is on attempt 1")
    void wrongAttemptIsIgnored() {
        Payment p = newPayment();

        assertIgnored(reply(new FundsReserved(p.id()), 2));

        assertThat(state(p.id())).isEqualTo("RESERVING");
        assertThat(stepAttempts(p.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-013 an ignored reply that is redelivered is a duplicate with no effect either")
    void ignoredReplyRedelivered() {
        Payment p = newPayment();
        Envelope wrong = reply(new RiskApproved(p.id()));
        deliver(wrong);
        Snapshot before = snapshotAll();

        IdempotentConsumer.Outcome outcome = deliver(wrong);

        assertThat(outcome).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        assertThat(snapshotAll()).isEqualTo(before);
    }

    // ---- poison ----

    @Test
    @DisplayName("REQ-PF-021 a reply for an unknown payment is poison: no inbox row, no effect, no reason in the text")
    void unknownPaymentIsPoison() {
        Envelope orphan = reply(new FundsRejected(UUID.randomUUID(), "LEAKY-REASON-42"));

        assertPoison(orphan, "LEAKY-REASON-42");
    }

    @Test
    @DisplayName("REQ-PF-021 a command on a reply topic is poison: nothing changes and no account id is in the text")
    void commandAsReplyIsPoison() {
        Payment p = newPayment();
        Envelope command = Envelope.of(new ReserveFunds(p.id(), "alice-secret", 5, "INR"), 1, Clock.systemUTC());

        assertPoison(command, "alice-secret");
        assertThat(state(p.id())).isEqualTo("RESERVING");
    }

    @Test
    @DisplayName("REQ-PF-021 a command for an unknown payment on a reply topic is poison too")
    void commandForUnknownPaymentIsPoison() {
        Envelope command =
                Envelope.of(new ReserveFunds(UUID.randomUUID(), "alice-secret", 5, "INR"), 1, Clock.systemUTC());

        assertPoison(command, "alice-secret");
    }
}
