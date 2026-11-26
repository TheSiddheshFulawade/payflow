package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The wallet refuses a payer wallet that is not the requester's (OWNER_MISMATCH): the payment ends REJECTED. */
class OwnerMismatchRejectionTest extends PaymentIntegrationTest {

    @Test
    @DisplayName("REQ-PF-003 a payment created by sub-42 queues ReserveFunds with requester sub-42")
    void reserveFundsNamesTheClient() {
        Payment p = payments.create("sub-42", freshKey(), body()).payment();

        assertThat(onlyOutbox(p.id(), ReserveFunds.class).payload())
                .isEqualTo(new ReserveFunds(p.id(), "alice", 500, "INR", "sub-42"));
    }

    @Test
    @DisplayName("REQ-PF-003 FundsRejected OWNER_MISMATCH ends REJECTED with the reason, no release, no deadline")
    void ownerMismatchEndsRejected() {
        Payment p = payments.create("sub-42", freshKey(), body()).payment();
        Envelope rejection = reply(new FundsRejected(p.id(), "OWNER_MISMATCH"));

        deliver(rejection);

        assertThat(state(p.id())).isEqualTo("REJECTED");
        assertThat(eventReason(p.id(), "REJECTED")).contains("OWNER_MISMATCH");
        assertThat(deadline(p.id())).isNull();
        assertThat(commands(p.id(), ReleaseFunds.class)).as("nothing was held").isEmpty();
        assertThat(outboxCount(p.id())).as("only the ReserveFunds").isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 a redelivered OWNER_MISMATCH changes nothing")
    void redeliveredOwnerMismatchIsIdempotent() {
        Payment p = payments.create("sub-42", freshKey(), body()).payment();
        Envelope rejection = reply(new FundsRejected(p.id(), "OWNER_MISMATCH"));
        deliver(rejection);
        Snapshot before = snapshotAll();

        deliver(rejection);
        deliver(resent(rejection));

        assertThat(snapshotAll()).isEqualTo(before);
        assertThat(state(p.id())).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("REQ-PF-013 a late OWNER_MISMATCH for a payment that already left RESERVING is ignored")
    void lateOwnerMismatchIsIgnored() {
        Payment p = newPayment();
        advanceTo(p.id(), com.siddheshfulawade.payflow.payment.domain.PaymentState.RISK_CHECKING);
        Snapshot before = snapshotAll();

        deliver(reply(new FundsRejected(p.id(), "OWNER_MISMATCH")));

        assertThat(state(p.id())).isEqualTo("RISK_CHECKING");
        assertThat(snapshotAll()).isEqualTo(before);
    }
}
