package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ReserveFunds: the first decision for a payment, and every repeat of it. */
class ReserveFundsTest extends WalletIntegrationTest {

    private final UUID payment = UUID.randomUUID();

    private void assertRejected(Envelope reply, String reason, int attempt) {
        assertThat(reply.payload()).isEqualTo(new FundsRejected(payment, reason));
        assertThat(reply.attempt()).isEqualTo(attempt);
        assertThat(reply.paymentId()).isEqualTo(payment);
    }

    // ---- first decision ----

    @Test
    @DisplayName("REQ-PF-030 reserve moves the amount from available to held, writes the hold and a RESERVE entry")
    void reserveHoldsTheAmount() {
        wallet("alice", 1_000);

        Envelope reply = apply(reserve(payment, "alice", 300, 3));

        assertThat(reply.payload()).isEqualTo(new FundsReserved(payment));
        assertThat(reply.attempt()).as("reply attempt = command attempt").isEqualTo(3);
        assertBalances("alice", 700, 300);
        assertThat(holdState(payment)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForMap("select payer, amount from hold where payment_id = ?", payment))
                .containsEntry("payer", "alice")
                .containsEntry("amount", 300L);
        assertThat(ledger(payment)).containsExactly("RESERVE alice 300");
    }

    @Test
    @DisplayName("REQ-PF-030 reserving exactly the available balance succeeds and leaves 0")
    void reserveWholeBalance() {
        wallet("alice", 500);

        assertThat(apply(reserve(payment, "alice", 500)).payload()).isEqualTo(new FundsReserved(payment));

        assertBalances("alice", 0, 500);
    }

    @Test
    @DisplayName("REQ-PF-030 one minor unit short is INSUFFICIENT_FUNDS, stored as a REJECTED hold, nothing moves")
    void reserveOneShort() {
        wallet("alice", 499);

        assertRejected(apply(reserve(payment, "alice", 500, 2)), "INSUFFICIENT_FUNDS", 2);

        assertBalances("alice", 499, 0);
        assertThat(holdState(payment)).isEqualTo("REJECTED");
        assertThat(holdReason(payment)).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(ledger(payment)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-030 reserve for an unknown wallet is WALLET_UNKNOWN and stored as REJECTED")
    void reserveUnknownWallet() {
        assertRejected(apply(reserve(payment, "ghost", 100)), "WALLET_UNKNOWN", 1);

        assertThat(holdState(payment)).isEqualTo("REJECTED");
        assertThat(holdReason(payment)).isEqualTo("WALLET_UNKNOWN");
        assertThat(ledger(payment)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FROZEN", "CLOSED"})
    @DisplayName("REQ-PF-030 reserve on a wallet that is not ACTIVE is WALLET_FROZEN even with enough money")
    void reserveOnInactiveWallet(String status) {
        wallet("alice", 1_000, status);

        assertRejected(apply(reserve(payment, "alice", 100)), "WALLET_FROZEN", 1);

        assertBalances("alice", 1_000, 0);
        assertThat(holdState(payment)).isEqualTo("REJECTED");
        assertThat(holdReason(payment)).isEqualTo("WALLET_FROZEN");
        assertThat(ledger(payment)).isEmpty();
    }

    // ---- repeats (the sweeper re-sends with a NEW message id) ----

    @Test
    @DisplayName(
            "REQ-PF-021 a re-sent ReserveFunds (new message id) for a RESERVED hold holds once and replies FundsReserved")
    void resentReserveHoldsOnce() {
        wallet("alice", 1_000);
        Envelope first = reserve(payment, "alice", 300, 1);
        apply(first);

        Envelope reply = apply(resentOnAttempt(first, 2));

        assertThat(reply.payload()).isEqualTo(new FundsReserved(payment));
        assertThat(reply.attempt()).as("reply carries the re-sent attempt").isEqualTo(2);
        assertBalances("alice", 700, 300);
        assertThat(ledger(payment)).containsExactly("RESERVE alice 300");
        assertThat(holdRows(payment)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 a re-sent ReserveFunds for a CAPTURED hold replies FundsReserved and moves nothing")
    void resentReserveAfterCapture() {
        wallet("alice", 1_000);
        wallet("bob", 0);
        Envelope first = reserve(payment, "alice", 300);
        apply(first);
        apply(capture(payment, "alice", "bob", 300));

        Envelope reply = apply(resent(first));

        assertThat(reply.payload()).isEqualTo(new FundsReserved(payment));
        assertBalances("alice", 700, 0);
        assertBalances("bob", 300, 0);
        assertThat(holdState(payment)).isEqualTo("CAPTURED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "CAPTURED"})
    @DisplayName("REQ-PF-021 same payment with a different amount is poison and changes nothing")
    void reserveWithDifferentAmount(String state) {
        madeHold(state);

        assertPoison(reserve(payment, "alice", 400), "alice", "400", "300");

        assertUnchanged(state);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "CAPTURED"})
    @DisplayName("REQ-PF-021 same payment with a different payer is poison and changes nothing")
    void reserveWithDifferentPayer(String state) {
        madeHold(state);
        wallet("carol", 1_000);

        assertPoison(reserve(payment, "carol", 300), "carol", "alice", "300");

        assertUnchanged(state);
        assertBalances("carol", 1_000, 0);
        assertThat(jdbc.queryForObject("select payer from hold where payment_id = ?", String.class, payment))
                .isEqualTo("alice");
    }

    @Test
    @DisplayName("REQ-PF-021 a mismatching reserve on a REJECTED or RELEASED hold keeps the stored answer")
    void mismatchOnFinalHoldKeepsAnswer() {
        wallet("alice", 100);
        apply(reserve(payment, "alice", 500));

        assertRejected(apply(reserve(payment, "alice", 1)), "INSUFFICIENT_FUNDS", 1);

        UUID released = UUID.randomUUID();
        apply(release(released, "alice", 50));
        Envelope reply = apply(reserve(released, "carol", 7));
        assertThat(reply.payload()).isEqualTo(new FundsRejected(released, "NO_ACTIVE_HOLD"));
    }

    private void madeHold(String state) {
        wallet("alice", 1_000);
        wallet("bob", 0);
        apply(reserve(payment, "alice", 300));
        if (state.equals("CAPTURED")) {
            apply(capture(payment, "alice", "bob", 300));
        }
    }

    private void assertUnchanged(String state) {
        boolean captured = state.equals("CAPTURED");
        assertBalances("alice", 700, captured ? 0 : 300);
        assertBalances("bob", captured ? 300 : 0, 0);
        assertThat(holdState(payment)).isEqualTo(state);
        assertThat(holdRows(payment)).isEqualTo(1);
        assertThat(ledger(payment)).hasSize(captured ? 3 : 1);
    }

    @Test
    @DisplayName("REQ-PF-021 a REJECTED decision is permanent: the same reason comes back even after money arrives")
    void rejectionIsSticky() {
        wallet("alice", 100);
        Envelope first = reserve(payment, "alice", 500);
        apply(first);
        jdbc.update("update wallet set available = 10000 where id = 'alice'");

        assertRejected(apply(resent(first)), "INSUFFICIENT_FUNDS", 1);

        assertBalances("alice", 10_000, 0);
        assertThat(holdState(payment)).isEqualTo("REJECTED");
        assertThat(ledger(payment)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-021 a WALLET_FROZEN rejection is permanent even after the wallet is unfrozen")
    void frozenRejectionIsSticky() {
        wallet("alice", 1_000, "FROZEN");
        Envelope first = reserve(payment, "alice", 100);
        apply(first);
        jdbc.update("update wallet set status = 'ACTIVE' where id = 'alice'");

        assertRejected(apply(resent(first)), "WALLET_FROZEN", 1);

        assertBalances("alice", 1_000, 0);
    }

    @Test
    @DisplayName("REQ-PF-021 a WALLET_UNKNOWN rejection is permanent even after the wallet is created")
    void unknownRejectionIsSticky() {
        Envelope first = reserve(payment, "alice", 100);
        apply(first);
        wallet("alice", 1_000);

        assertRejected(apply(resent(first)), "WALLET_UNKNOWN", 1);

        assertBalances("alice", 1_000, 0);
    }

    @Test
    @DisplayName("REQ-PF-021 a ReserveFunds after the hold was RELEASED is NO_ACTIVE_HOLD and holds nothing")
    void reserveAfterRelease() {
        wallet("alice", 1_000);
        Envelope first = reserve(payment, "alice", 300);
        apply(first);
        apply(release(payment, "alice", 300));

        assertRejected(apply(resent(first)), "NO_ACTIVE_HOLD", 1);

        assertBalances("alice", 1_000, 0);
        assertThat(holdState(payment)).isEqualTo("RELEASED");
        assertThat(ledger(payment)).containsExactlyInAnyOrder("RESERVE alice 300", "RELEASE alice 300");
    }

    // ---- REQ-PF-021 with the inbox ----

    @Test
    @DisplayName("REQ-PF-021 redelivered ReserveFunds (same message id) holds once and re-sends the same reply")
    void redeliveredReserveHoldsOnce() {
        wallet("alice", 1_000);
        Envelope command = reserve(payment, "alice", 300);

        IdempotentConsumer.Outcome first = consumer.handle(command, handler::handle);
        IdempotentConsumer.Outcome second = consumer.handle(command, handler::handle);

        assertThat(first).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(second).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        assertBalances("alice", 700, 300);
        assertThat(ledger(payment)).hasSize(1);
        assertThat(outboxReplies(payment))
                .as("the stored reply is queued again, byte for byte")
                .hasSize(2)
                .allSatisfy(reply -> assertThat(reply.payload()).isEqualTo(new FundsReserved(payment)))
                .satisfies(replies -> assertThat(replies.get(0).messageId())
                        .isEqualTo(replies.get(1).messageId()));
    }
}
