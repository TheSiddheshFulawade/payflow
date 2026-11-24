package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A failure in the middle of a reply leaves nothing behind, and the redelivery finishes the job (REQ-PF-014, 020). */
class ReplyAtomicityTest extends PaymentIntegrationTest {

    @Test
    @DisplayName(
            "REQ-PF-014 a stale version rolls everything back, the inbox row too, and the redelivery then succeeds")
    void staleVersionRollsBackAndRedeliveryWorks() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));
        Snapshot before = snapshotAll();
        makeEveryPaymentUpdateStale();

        assertThatThrownBy(() -> deliver(reserved)).isInstanceOf(StaleVersionException.class);

        assertThat(snapshotAll())
                .as("no state change, no event, no CheckRisk, and no inbox row that would block the redelivery")
                .isEqualTo(before);

        dropFailureTriggers();
        assertThat(deliver(reserved)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CheckRisk))
                .hasSize(1);
    }

    @Test
    @DisplayName("REQ-PF-020 when the next command cannot be appended, the state change is not kept")
    void failedAppendKeepsTheOldState() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));
        Snapshot before = snapshotAll();
        makeOutboxInsertFail("risk.commands");

        assertThatThrownBy(() -> deliver(reserved)).isInstanceOf(RuntimeException.class);

        assertThat(state(p.id())).isEqualTo("RESERVING");
        assertThat(version(p.id())).isEqualTo(1);
        assertThat(snapshotAll())
                .as("no half transition: no event, no inbox row, no command")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("REQ-PF-020 after a failed append the redelivered reply moves the payment once with one command")
    void redeliveryAfterFailedAppend() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));
        makeOutboxInsertFail("risk.commands");
        assertThatThrownBy(() -> deliver(reserved)).isInstanceOf(RuntimeException.class);
        dropFailureTriggers();

        deliver(reserved);
        deliver(reserved);

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(version(p.id())).isEqualTo(2);
        assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CheckRisk))
                .hasSize(1);
        assertThat(eventCount(p.id())).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "REQ-PF-020 a failed capture append leaves the payment in RISK_CHECKING, not CAPTURING without a command")
    void failedCaptureAppend() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        Envelope approved = reply(new RiskApproved(p.id()));
        makeOutboxInsertFail("wallet.commands");

        assertThatThrownBy(() -> deliver(approved)).isInstanceOf(RuntimeException.class);

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CaptureFunds))
                .isEmpty();
        dropFailureTriggers();
        deliver(approved);
        assertThat(state(p.id())).isEqualTo("CAPTURING");
        assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CaptureFunds))
                .hasSize(1);
    }
}
