package com.siddheshfulawade.payflow.payment.domain;

import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.paymentOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The saga's side of the owner check (REQ-PF-003): who is named as the requester, and what OWNER_MISMATCH does. */
class OwnerCheckSagaTest {

    @Test
    @DisplayName("REQ-PF-003 start() names the creating client as the requester, not the payer wallet")
    void requesterIsTheClient() {
        Payment payment = paymentOf("sub-42", PaymentState.RECEIVED);

        Transition start = SagaStateMachine.start(payment);

        assertThat(payment.payer()).isEqualTo("alice");
        assertThat(start.command())
                .contains(new ReserveFunds(payment.id(), "alice", payment.amount(), "INR", "sub-42"));
    }

    @Test
    @DisplayName("REQ-PF-003 OWNER_MISMATCH while RESERVING ends REJECTED with that reason and sends nothing (no hold)")
    void ownerMismatchRejects() {
        Payment payment = paymentOf("sub-42", PaymentState.RESERVING);

        Transition t = SagaStateMachine.on(payment, new FundsRejected(payment.id(), "OWNER_MISMATCH"), 1)
                .orElseThrow();

        assertThat(t.to()).isEqualTo(PaymentState.REJECTED);
        assertThat(t.reason()).contains("OWNER_MISMATCH");
        assertThat(t.command()).as("nothing was held, so nothing is released").isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-013 a late OWNER_MISMATCH after the payment moved on is ignored")
    void lateOwnerMismatchIsIgnored() {
        for (PaymentState state : new PaymentState[] {
            PaymentState.RISK_CHECKING, PaymentState.CAPTURING, PaymentState.COMPLETED, PaymentState.REVERSED
        }) {
            Payment payment = paymentOf("sub-42", state);

            assertThat(SagaStateMachine.on(payment, new FundsRejected(payment.id(), "OWNER_MISMATCH"), 1))
                    .as("state %s", state)
                    .isEmpty();
        }
    }
}
