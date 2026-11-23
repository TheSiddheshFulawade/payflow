package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CaptureFunds: payer's held money becomes the payee's available money, in one transaction. */
class CaptureFundsTest extends WalletIntegrationTest {

    private final UUID payment = UUID.randomUUID();

    @BeforeEach
    void wallets() {
        wallet("alice", 1_000);
        wallet("bob", 50);
        apply(reserve(payment, "alice", 300));
    }

    private void assertFailed(Envelope reply, String reason, int attempt) {
        assertThat(reply.payload()).isEqualTo(new CaptureFailed(payment, reason));
        assertThat(reply.attempt()).isEqualTo(attempt);
    }

    @Test
    @DisplayName("REQ-PF-030 capture debits the payer's held, credits the payee's available, writes both entries")
    void captureMovesMoney() {
        Envelope reply = apply(capture(payment, "alice", "bob", 300, 2));

        assertThat(reply.payload()).isEqualTo(new FundsCaptured(payment));
        assertThat(reply.attempt()).isEqualTo(2);
        assertBalances("alice", 700, 0);
        assertBalances("bob", 350, 0);
        assertThat(holdState(payment)).isEqualTo("CAPTURED");
        assertThat(ledger(payment))
                .containsExactlyInAnyOrder("RESERVE alice 300", "CAPTURE_DEBIT alice 300", "CAPTURE_CREDIT bob 300");
        assertThat(totalMoney()).isEqualTo(1_050);
    }

    @Test
    @DisplayName("REQ-PF-021 a re-sent CaptureFunds (new message id) replies FundsCaptured and credits once")
    void resentCaptureCreditsOnce() {
        Envelope first = capture(payment, "alice", "bob", 300, 1);
        apply(first);

        Envelope reply = apply(resentOnAttempt(first, 3));

        assertThat(reply.payload()).isEqualTo(new FundsCaptured(payment));
        assertThat(reply.attempt()).isEqualTo(3);
        assertBalances("alice", 700, 0);
        assertBalances("bob", 350, 0);
        assertThat(ledger(payment)).hasSize(3);
    }

    @Test
    @DisplayName("REQ-PF-030 capture to an unknown payee is CaptureFailed(PAYEE_UNKNOWN) and the hold stays RESERVED")
    void payeeUnknown() {
        assertFailed(apply(capture(payment, "alice", "ghost", 300, 2)), "PAYEE_UNKNOWN", 2);

        assertBalances("alice", 700, 300);
        assertThat(holdState(payment)).isEqualTo("RESERVED");
        assertThat(ledger(payment)).containsExactly("RESERVE alice 300");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FROZEN", "CLOSED"})
    @DisplayName(
            "REQ-PF-030 capture to a payee that is not ACTIVE fails with PAYEE_NOT_ACTIVE; the hold stays RESERVED")
    void payeeNotActive(String status) {
        jdbc.update("update wallet set status = ? where id = 'bob'", status);

        assertFailed(apply(capture(payment, "alice", "bob", 300)), "PAYEE_NOT_ACTIVE", 1);

        assertBalances("alice", 700, 300);
        assertBalances("bob", 50, 0);
        assertThat(holdState(payment)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("REQ-PF-030 after PAYEE_NOT_ACTIVE the compensating release returns the money to the payer")
    void failedCaptureThenRelease() {
        jdbc.update("update wallet set status = 'FROZEN' where id = 'bob'");
        apply(capture(payment, "alice", "bob", 300));

        Envelope reply = apply(release(payment, "alice", 300));

        assertThat(reply.payload()).isEqualTo(new FundsReleased(payment));
        assertBalances("alice", 1_000, 0);
        assertBalances("bob", 50, 0);
        assertThat(totalMoney()).isEqualTo(1_050);
    }

    @Test
    @DisplayName("REQ-PF-030 a failed capture is not permanent: once the payee is ACTIVE again a retry succeeds")
    void captureRetryAfterPayeeReactivated() {
        jdbc.update("update wallet set status = 'FROZEN' where id = 'bob'");
        Envelope first = capture(payment, "alice", "bob", 300);
        apply(first);
        jdbc.update("update wallet set status = 'ACTIVE' where id = 'bob'");

        assertThat(apply(resent(first)).payload()).isEqualTo(new FundsCaptured(payment));

        assertBalances("alice", 700, 0);
        assertBalances("bob", 350, 0);
    }

    @Test
    @DisplayName("REQ-PF-030 capture with a different amount is poison, nothing moves")
    void amountMismatch() {
        assertPoison(capture(payment, "alice", "bob", 299), "alice", "bob", "299", "300");

        assertBalances("alice", 700, 300);
        assertBalances("bob", 50, 0);
        assertThat(holdState(payment)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("REQ-PF-030 capture naming a different payer is poison, nothing moves")
    void payerMismatch() {
        wallet("carol", 1_000, 300, "ACTIVE");

        assertPoison(capture(payment, "carol", "bob", 300), "carol", "alice", "300");

        assertBalances("alice", 700, 300);
        assertBalances("carol", 1_000, 300);
        assertBalances("bob", 50, 0);
    }

    @Test
    @DisplayName("REQ-PF-030 on a CAPTURED hold a different amount or payer is poison and nothing moves")
    void mismatchAfterCapture() {
        apply(capture(payment, "alice", "bob", 300));

        assertPoison(capture(payment, "alice", "bob", 301), "301", "300");
        assertPoison(capture(payment, "carol", "bob", 300), "carol", "alice");

        assertBalances("alice", 700, 0);
        assertBalances("bob", 350, 0);
        assertThat(ledger(payment)).hasSize(3);
    }

    @Test
    @DisplayName("REQ-PF-021 capture of a CAPTURED hold naming another payee is poison; the same payee is a no-op")
    void otherPayeeAfterCapture() {
        wallet("dave", 0);
        apply(capture(payment, "alice", "bob", 300));

        assertPoison(capture(payment, "alice", "dave", 300), "dave", "bob");
        Envelope again = apply(capture(payment, "alice", "bob", 300));

        assertThat(again.payload()).isEqualTo(new FundsCaptured(payment));
        assertBalances("bob", 350, 0);
        assertBalances("dave", 0, 0);
        assertThat(ledger(payment)).hasSize(3);
    }

    @Test
    @DisplayName("REQ-PF-021 the hold's payee column is null until capture and then names the payee")
    void payeeColumn() {
        assertThat(payeeOf(payment)).isNull();

        apply(capture(payment, "alice", "bob", 300));

        assertThat(payeeOf(payment)).isEqualTo("bob");
    }

    @Test
    @DisplayName("REQ-PF-021 a failed capture and a release leave the hold's payee null")
    void payeeStaysNull() {
        jdbc.update("update wallet set status = 'FROZEN' where id = 'bob'");
        apply(capture(payment, "alice", "bob", 300));
        assertThat(payeeOf(payment)).isNull();

        apply(release(payment, "alice", 300));

        assertThat(payeeOf(payment)).isNull();
    }

    private String payeeOf(UUID id) {
        return jdbc.queryForObject("select payee from hold where payment_id = ?", String.class, id);
    }

    @Test
    @DisplayName("REQ-PF-030 capture without any hold is CaptureFailed(NO_ACTIVE_HOLD) and moves nothing")
    void noHold() {
        UUID other = UUID.randomUUID();

        Envelope reply = apply(capture(other, "alice", "bob", 300, 2));

        assertThat(reply.payload()).isEqualTo(new CaptureFailed(other, "NO_ACTIVE_HOLD"));
        assertThat(reply.attempt()).isEqualTo(2);
        assertBalances("alice", 700, 300);
        assertBalances("bob", 50, 0);
        assertThat(ledger(other)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-030 capture of a RELEASED hold is NO_ACTIVE_HOLD: released money can never be captured")
    void captureAfterRelease() {
        apply(release(payment, "alice", 300));

        assertFailed(apply(capture(payment, "alice", "bob", 300)), "NO_ACTIVE_HOLD", 1);

        assertBalances("alice", 1_000, 0);
        assertBalances("bob", 50, 0);
        assertThat(holdState(payment)).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("REQ-PF-030 capture of a REJECTED hold is NO_ACTIVE_HOLD")
    void captureOfRejectedHold() {
        UUID rejected = UUID.randomUUID();
        apply(reserve(rejected, "alice", 5_000));

        Envelope reply = apply(capture(rejected, "alice", "bob", 5_000));

        assertThat(reply.payload()).isEqualTo(new CaptureFailed(rejected, "NO_ACTIVE_HOLD"));
        assertBalances("bob", 50, 0);
        assertThat(holdState(rejected)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("REQ-PF-021 redelivered CaptureFunds (same message id) credits once and re-sends the same reply")
    void redeliveredCaptureCreditsOnce() {
        Envelope command = capture(payment, "alice", "bob", 300);

        consumer.handle(command, handler::handle);
        consumer.handle(command, handler::handle);

        assertBalances("bob", 350, 0);
        assertThat(outboxReplies(payment).stream().filter(e -> e.payload() instanceof FundsCaptured))
                .hasSize(2)
                .allSatisfy(e ->
                        assertThat(e.messageId()).isEqualTo(storedReply(command).messageId()));
    }
}
