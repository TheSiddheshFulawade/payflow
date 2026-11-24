package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentFixtures;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Until PF-06 a reply that asks for compensation must not be swallowed (its inbox row would hide it for good): the
 * handler throws, so the transaction rolls back and the reply ends on the dead-letter topic. PF-06 replaces this test.
 */
class CompensationPendingTest {

    private final Payments payments = mock(Payments.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final ReplyHandler handler = new ReplyHandler(
            payments,
            outbox,
            new StepDeadlines(Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10)),
            Clock.systemUTC());

    private Envelope replyTo(Payment payment, Reply reply, int attempt) {
        when(payments.find(payment.id())).thenReturn(Optional.of(payment));
        return Envelope.of(reply, attempt, Clock.systemUTC());
    }

    private void assertParked(Envelope message, String mustNotAppear) {
        assertThatThrownBy(() -> handler.handle(message))
                .isInstanceOf(IllegalStateException.class)
                .message()
                .contains("PF-06")
                .doesNotContain(mustNotAppear);
    }

    @AfterEach
    void nothingIsEverWritten() {
        verify(payments, never()).update(any(), any());
        verify(payments, never()).addEvent(any());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("REQ-PF-011 RiskRejected while RISK_CHECKING is not swallowed: it throws until PF-06 compensates")
    void riskRejectionThrows() {
        Payment p = PaymentFixtures.payment(PaymentState.RISK_CHECKING);

        assertParked(replyTo(p, new RiskRejected(p.id(), "LEAKY-RULE"), 1), "LEAKY-RULE");
    }

    @Test
    @DisplayName("REQ-PF-011 CaptureFailed while CAPTURING is not swallowed: it throws until PF-06 compensates")
    void captureFailureThrows() {
        Payment p = PaymentFixtures.payment(PaymentState.CAPTURING);

        assertParked(replyTo(p, new CaptureFailed(p.id(), "LEAKY-REASON"), 1), "LEAKY-REASON");
    }

    @Test
    @DisplayName("REQ-PF-013 a failure of another attempt, or late, is ignored like any other reply")
    void otherAttemptOrLateIsIgnored() {
        Payment checking = PaymentFixtures.payment(PaymentState.RISK_CHECKING);
        Payment completed = PaymentFixtures.payment(PaymentState.COMPLETED);

        assertThat(handler.handle(replyTo(checking, new RiskRejected(checking.id(), "VELOCITY"), 2)))
                .isEmpty();
        assertThat(handler.handle(replyTo(completed, new CaptureFailed(completed.id(), "PAYEE_FROZEN"), 1)))
                .isEmpty();
    }
}
