package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariant: the reserve decision for a payment is made once and is permanent, so a payment can never acquire a
 * hold after it was released or rejected, however the messages arrive.
 */
class OutOfOrderTest extends WalletIntegrationTest {

    private final UUID payment = UUID.randomUUID();

    @Test
    @DisplayName("REQ-PF-030 ReleaseFunds then a late ReserveFunds: FundsReleased, then NO_ACTIVE_HOLD, nothing held")
    void lateReserveAfterRelease() {
        wallet("alice", 1_000);

        Envelope released = apply(release(payment, "alice", 300));
        Envelope reserved = apply(reserve(payment, "alice", 300));

        assertThat(released.payload()).isEqualTo(new FundsReleased(payment));
        assertThat(reserved.payload()).isEqualTo(new FundsRejected(payment, "NO_ACTIVE_HOLD"));
        assertThat(totalHeld()).isZero();
        assertBalances("alice", 1_000, 0);
        assertThat(holdState(payment)).isEqualTo("RELEASED");
        assertThat(ledger(payment)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-030 a late ReserveFunds never holds, however often it is re-sent")
    void lateReserveRepeatedNeverHolds() {
        wallet("alice", 1_000);
        apply(release(payment, "alice", 300));
        Envelope late = reserve(payment, "alice", 300);

        for (int i = 0; i < 3; i++) {
            assertThat(apply(resent(late)).payload()).isEqualTo(new FundsRejected(payment, "NO_ACTIVE_HOLD"));
        }

        assertThat(totalHeld()).isZero();
    }

    @Test
    @DisplayName("REQ-PF-030 INSUFFICIENT_FUNDS stays the answer after money arrives; a release then changes nothing")
    void stickyRejection() {
        wallet("alice", 100);
        Envelope first = reserve(payment, "alice", 500);
        assertThat(apply(first).payload()).isEqualTo(new FundsRejected(payment, "INSUFFICIENT_FUNDS"));
        jdbc.update("update wallet set available = available + 5000 where id = 'alice'");

        assertThat(apply(resent(first)).payload()).isEqualTo(new FundsRejected(payment, "INSUFFICIENT_FUNDS"));
        assertThat(apply(release(payment, "alice", 500)).payload()).isEqualTo(new FundsReleased(payment));
        assertThat(apply(resent(first)).payload()).isEqualTo(new FundsRejected(payment, "INSUFFICIENT_FUNDS"));

        assertThat(totalHeld()).isZero();
        assertBalances("alice", 5_100, 0);
        assertThat(holdState(payment)).isEqualTo("REJECTED");
    }
}
