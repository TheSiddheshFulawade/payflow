package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ReleaseFunds: compensation. It must always end with the funds back, or a tombstone that blocks a late reserve. */
class ReleaseFundsTest extends WalletIntegrationTest {

    private final UUID payment = UUID.randomUUID();

    @Test
    @DisplayName("REQ-PF-030 release returns the held amount to available, marks RELEASED, writes a RELEASE entry")
    void releaseReturnsFunds() {
        wallet("alice", 1_000);
        apply(reserve(payment, "alice", 300));

        Envelope reply = apply(release(payment, "alice", 300, 4));

        assertThat(reply.payload()).isEqualTo(new FundsReleased(payment));
        assertThat(reply.attempt()).isEqualTo(4);
        assertBalances("alice", 1_000, 0);
        assertThat(holdState(payment)).isEqualTo("RELEASED");
        assertThat(ledger(payment)).containsExactlyInAnyOrder("RESERVE alice 300", "RELEASE alice 300");
    }

    @Test
    @DisplayName("REQ-PF-030 release returns the amount of the HOLD, not the amount a command claims")
    void releaseUsesTheHoldAmount() {
        wallet("alice", 1_000);
        apply(reserve(payment, "alice", 300));

        apply(release(payment, "alice", 9_999));

        assertBalances("alice", 1_000, 0);
        assertThat(ledger(payment)).containsExactlyInAnyOrder("RESERVE alice 300", "RELEASE alice 300");
    }

    @Test
    @DisplayName("REQ-PF-030 release works for a FROZEN wallet: the money must be able to come back")
    void releaseOnFrozenWallet() {
        wallet("alice", 1_000);
        apply(reserve(payment, "alice", 300));
        jdbc.update("update wallet set status = 'FROZEN' where id = 'alice'");

        assertThat(apply(release(payment, "alice", 300)).payload()).isEqualTo(new FundsReleased(payment));

        assertBalances("alice", 1_000, 0);
        assertThat(holdState(payment)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("REQ-PF-021 a re-sent ReleaseFunds (new message id) replies FundsReleased and releases once")
    void resentReleaseReleasesOnce() {
        wallet("alice", 1_000);
        apply(reserve(payment, "alice", 300));
        Envelope first = release(payment, "alice", 300, 1);
        apply(first);

        Envelope reply = apply(resentOnAttempt(first, 2));

        assertThat(reply.payload()).isEqualTo(new FundsReleased(payment));
        assertThat(reply.attempt()).isEqualTo(2);
        assertBalances("alice", 1_000, 0);
        assertThat(ledger(payment)).containsExactlyInAnyOrder("RESERVE alice 300", "RELEASE alice 300");
    }

    @Test
    @DisplayName("REQ-PF-021 release of a REJECTED hold replies FundsReleased, moves nothing, keeps REJECTED")
    void releaseOfRejectedHold() {
        wallet("alice", 100);
        apply(reserve(payment, "alice", 500));

        assertThat(apply(release(payment, "alice", 500)).payload()).isEqualTo(new FundsReleased(payment));

        assertBalances("alice", 100, 0);
        assertThat(holdState(payment)).isEqualTo("REJECTED");
        assertThat(ledger(payment)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-021 release with no hold row writes a RELEASED tombstone and moves no money")
    void releaseWithoutHoldLeavesTombstone() {
        wallet("alice", 1_000);

        Envelope reply = apply(release(payment, "alice", 300, 2));

        assertThat(reply.payload()).isEqualTo(new FundsReleased(payment));
        assertThat(reply.attempt()).isEqualTo(2);
        assertThat(holdState(payment)).isEqualTo("RELEASED");
        assertBalances("alice", 1_000, 0);
        assertThat(ledger(payment)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-021 release with no hold row works even when the wallet does not exist")
    void releaseWithoutHoldAndWithoutWallet() {
        assertThat(apply(release(payment, "ghost", 300)).payload()).isEqualTo(new FundsReleased(payment));

        assertThat(holdState(payment)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("REQ-PF-021 release of a CAPTURED hold tells the truth: FundsCaptured, nothing moves")
    void releaseOfCapturedHold() {
        wallet("alice", 1_000);
        wallet("bob", 0);
        apply(reserve(payment, "alice", 300));
        apply(capture(payment, "alice", "bob", 300));

        Envelope reply = apply(release(payment, "alice", 300, 5));

        assertThat(reply.payload()).isEqualTo(new FundsCaptured(payment));
        assertThat(reply.attempt()).isEqualTo(5);
        assertBalances("alice", 700, 0);
        assertBalances("bob", 300, 0);
        assertThat(holdState(payment)).isEqualTo("CAPTURED");
    }
}
