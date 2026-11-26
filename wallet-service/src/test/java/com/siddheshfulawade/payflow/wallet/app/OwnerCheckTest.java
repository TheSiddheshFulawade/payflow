package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * REQ-PF-003: money leaves a wallet only for its owner. ReserveFunds names the requester (the JWT subject of the
 * payment's creator); the wallet checks it against the payer wallet's owner_sub before it holds anything. (The fixture
 * seeds wallet "alice" with owner "owner-alice".)
 */
class OwnerCheckTest extends WalletIntegrationTest {

    private final UUID payment = UUID.randomUUID();

    private void assertOwnerMismatch(Envelope reply, int attempt) {
        assertThat(reply.payload()).isEqualTo(new FundsRejected(payment, "OWNER_MISMATCH"));
        assertThat(reply.attempt()).isEqualTo(attempt);
    }

    @Test
    @DisplayName("REQ-PF-003 a reserve by the wallet's owner holds the money")
    void ownerReserves() {
        wallet("alice", 1_000);

        Envelope reply = apply(reserveAs("owner-alice", payment, "alice", 300, 1));

        assertThat(reply.payload()).isEqualTo(new FundsReserved(payment));
        assertBalances("alice", 700, 300);
    }

    @Test
    @DisplayName(
            "REQ-PF-003 a reserve by someone else is FundsRejected OWNER_MISMATCH: nothing held, balances unchanged")
    void otherRequesterIsRejected() {
        wallet("alice", 1_000);
        wallet("bob", 500);

        Envelope reply = apply(reserveAs("owner-bob", payment, "alice", 300, 2));

        assertOwnerMismatch(reply, 2);
        assertBalances("alice", 1_000, 0);
        assertBalances("bob", 500, 0);
        assertThat(ledger(payment)).isEmpty();
        assertThat(totalHeld()).isZero();
        assertThat(holdState(payment))
                .as("the refusal is recorded like any other")
                .isEqualTo("REJECTED");
        assertThat(holdReason(payment)).isEqualTo("OWNER_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner-alic", "OWNER-ALICE", "owner-alice2", "alice"})
    @DisplayName("REQ-PF-003 the owner comparison is exact: a prefix, other case or extra character is a mismatch")
    void comparisonIsExact(String requester) {
        wallet("alice", 1_000);

        Envelope reply = apply(reserveAs(requester, payment, "alice", 300, 1));

        assertOwnerMismatch(reply, 1);
        assertBalances("alice", 1_000, 0);
    }

    @Test
    @DisplayName("REQ-PF-003 the owner check comes before the funds check: a stranger learns nothing about the balance")
    void mismatchDoesNotRevealTheBalance() {
        wallet("alice", 10); // would be INSUFFICIENT_FUNDS for 300
        wallet("frozen", 1_000, "FROZEN"); // would be WALLET_FROZEN

        assertOwnerMismatch(apply(reserveAs("stranger", payment, "alice", 300, 1)), 1);
        UUID other = UUID.randomUUID();
        Envelope second = apply(reserveAs("stranger", other, "frozen", 100, 1));

        assertThat(second.payload()).isEqualTo(new FundsRejected(other, "OWNER_MISMATCH"));
    }

    @Test
    @DisplayName("REQ-PF-030 an unknown wallet is still WALLET_UNKNOWN: there is no owner to compare")
    void unknownWalletKeepsItsReason() {
        Envelope reply = apply(reserveAs("owner-ghost", payment, "ghost", 100, 1));

        assertThat(reply.payload()).isEqualTo(new FundsRejected(payment, "WALLET_UNKNOWN"));
    }

    @Test
    @DisplayName("REQ-PF-021 a redelivered mismatching ReserveFunds (same message id) re-sends the stored reply once")
    void redeliveryIsIdempotent() {
        wallet("alice", 1_000);
        Envelope command = reserveAs("owner-bob", payment, "alice", 300, 1);
        Envelope first = apply(command);

        Envelope again = apply(command);

        assertOwnerMismatch(first, 1);
        assertThat(again).isEqualTo(first);
        assertThat(holdRows(payment)).isEqualTo(1);
        assertThat(outboxReplies(payment)).hasSize(1);
        assertBalances("alice", 1_000, 0);
    }

    @Test
    @DisplayName(
            "REQ-PF-021 a re-sent mismatching ReserveFunds (new message id, next attempt) answers OWNER_MISMATCH again")
    void resentMismatchAnswersAgain() {
        wallet("alice", 1_000);
        Envelope first = reserveAs("owner-bob", payment, "alice", 300, 1);
        apply(first);

        Envelope reply = apply(resentOnAttempt(first, 2));

        assertOwnerMismatch(reply, 2);
        assertBalances("alice", 1_000, 0);
        assertThat(holdRows(payment)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-003 a rejected mismatch cannot be turned into a hold by re-sending it as the owner")
    void ownerCannotReviveARejectedPayment() {
        wallet("alice", 1_000);
        apply(reserveAs("owner-bob", payment, "alice", 300, 1));

        Envelope reply = apply(reserveAs("owner-alice", payment, "alice", 300, 2));

        assertOwnerMismatch(reply, 2);
        assertBalances("alice", 1_000, 0);
    }
}
