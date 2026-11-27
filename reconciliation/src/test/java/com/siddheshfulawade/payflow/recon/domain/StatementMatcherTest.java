package com.siddheshfulawade.payflow.recon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * REQ-PF-040. Status correspondence (the rule under test):
 * COMPLETED = CAPTURED (amounts must be equal); REVERSED = RELEASED (nothing may be captured);
 * REJECTED = REJECTED (nothing captured) or no wallet line at all (risk rejected it before any hold).
 * A status difference wins over an amount difference: amounts of different outcomes cannot be compared.
 */
class StatementMatcherTest {

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    private static PaymentLine pay(int n, long amount, String state) {
        return new PaymentLine(id(n), amount, "INR", state);
    }

    private static WalletLine wal(int n, long captured, String status) {
        return new WalletLine(id(n), captured, status);
    }

    private static Outcome single(PaymentLine p, WalletLine w) {
        List<ReconResult> results =
                StatementMatcher.match(p == null ? List.of() : List.of(p), w == null ? List.of() : List.of(w));
        assertThat(results).hasSize(1);
        return results.get(0).outcome();
    }

    @Test
    @DisplayName("REQ-PF-040 COMPLETED and CAPTURED with the same amount is MATCHED")
    void completedCaptured() {
        assertThat(single(pay(1, 500, "COMPLETED"), wal(1, 500, "CAPTURED"))).isEqualTo(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-040 REVERSED and RELEASED with nothing captured is MATCHED")
    void reversedReleased() {
        assertThat(single(pay(1, 500, "REVERSED"), wal(1, 0, "RELEASED"))).isEqualTo(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-040 REJECTED and REJECTED is MATCHED")
    void rejectedRejected() {
        assertThat(single(pay(1, 500, "REJECTED"), wal(1, 0, "REJECTED"))).isEqualTo(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-040 REJECTED with no wallet movement is MATCHED (rejected before any hold)")
    void rejectedWithoutWalletLine() {
        assertThat(single(pay(1, 500, "REJECTED"), null)).isEqualTo(Outcome.MATCHED);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED", "REVERSED"})
    @DisplayName("REQ-PF-040 COMPLETED or REVERSED without a wallet line is MISSING_IN_WALLET")
    void missingInWallet(String state) {
        assertThat(single(pay(1, 500, state), null)).isEqualTo(Outcome.MISSING_IN_WALLET);
    }

    @ParameterizedTest
    @CsvSource({"CAPTURED,500", "RELEASED,0", "REJECTED,0"})
    @DisplayName("REQ-PF-040 a wallet line without a payment line is MISSING_IN_PAYMENTS")
    void missingInPayments(String status, long captured) {
        assertThat(single(null, wal(1, captured, status))).isEqualTo(Outcome.MISSING_IN_PAYMENTS);
    }

    @Test
    @DisplayName("REQ-PF-040 a different captured amount is AMOUNT_MISMATCH")
    void amountMismatch() {
        assertThat(single(pay(1, 500, "COMPLETED"), wal(1, 499, "CAPTURED"))).isEqualTo(Outcome.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("REQ-PF-040 money captured on a RELEASED wallet line of a REVERSED payment is AMOUNT_MISMATCH")
    void movedMoneyOnReversed() {
        assertThat(single(pay(1, 500, "REVERSED"), wal(1, 500, "RELEASED"))).isEqualTo(Outcome.AMOUNT_MISMATCH);
    }

    @ParameterizedTest
    @CsvSource({
        "COMPLETED,RELEASED",
        "COMPLETED,REJECTED",
        "REVERSED,CAPTURED",
        "REVERSED,REJECTED",
        "REJECTED,CAPTURED",
        "REJECTED,RELEASED"
    })
    @DisplayName("REQ-PF-040 statuses that do not correspond are STATUS_MISMATCH")
    void statusMismatch(String state, String status) {
        long captured = status.equals("CAPTURED") ? 500 : 0;
        assertThat(single(pay(1, 500, state), wal(1, captured, status))).isEqualTo(Outcome.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("REQ-PF-040 when status and amount both differ, STATUS_MISMATCH wins")
    void statusWinsOverAmount() {
        assertThat(single(pay(1, 500, "COMPLETED"), wal(1, 0, "RELEASED"))).isEqualTo(Outcome.STATUS_MISMATCH);
    }

    @Test
    @DisplayName("REQ-PF-040 amount 0 and Long.MAX_VALUE compare exactly (no overflow, no rounding)")
    void extremeAmounts() {
        assertThat(single(pay(1, 0, "COMPLETED"), wal(1, 0, "CAPTURED"))).isEqualTo(Outcome.MATCHED);
        assertThat(single(pay(1, Long.MAX_VALUE, "COMPLETED"), wal(1, Long.MAX_VALUE, "CAPTURED")))
                .isEqualTo(Outcome.MATCHED);
        assertThat(single(pay(1, Long.MAX_VALUE, "COMPLETED"), wal(1, Long.MAX_VALUE - 1, "CAPTURED")))
                .isEqualTo(Outcome.AMOUNT_MISMATCH);
        assertThat(single(pay(1, 0, "COMPLETED"), wal(1, Long.MAX_VALUE, "CAPTURED")))
                .isEqualTo(Outcome.AMOUNT_MISMATCH);
    }

    @Test
    @DisplayName("REQ-PF-040 two empty statements give an empty report")
    void bothEmpty() {
        assertThat(StatementMatcher.match(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-040 an empty wallet statement makes every payment MISSING_IN_WALLET (a wallet outage is loud)")
    void emptyWalletStatement() {
        List<ReconResult> results =
                StatementMatcher.match(List.of(pay(1, 5, "COMPLETED"), pay(2, 6, "REVERSED")), List.of());

        assertThat(results)
                .extracting(ReconResult::outcome)
                .containsOnly(Outcome.MISSING_IN_WALLET)
                .hasSize(2);
    }

    @Test
    @DisplayName("REQ-PF-040 an empty payment statement makes every wallet line MISSING_IN_PAYMENTS")
    void emptyPaymentStatement() {
        List<ReconResult> results =
                StatementMatcher.match(List.of(), List.of(wal(1, 5, "CAPTURED"), wal(2, 0, "RELEASED")));

        assertThat(results)
                .extracting(ReconResult::outcome)
                .containsOnly(Outcome.MISSING_IN_PAYMENTS)
                .hasSize(2);
    }

    @Test
    @DisplayName("REQ-PF-040 one result per distinct payment id over both statements, ordered by payment id")
    void oneResultPerIdOrdered() {
        List<ReconResult> results = StatementMatcher.match(
                List.of(pay(3, 30, "COMPLETED"), pay(1, 10, "COMPLETED"), pay(4, 40, "COMPLETED")),
                List.of(wal(5, 50, "CAPTURED"), wal(3, 30, "CAPTURED"), wal(1, 11, "CAPTURED")));

        assertThat(results)
                .containsExactly(
                        new ReconResult(id(1), Outcome.AMOUNT_MISMATCH),
                        new ReconResult(id(3), Outcome.MATCHED),
                        new ReconResult(id(4), Outcome.MISSING_IN_WALLET),
                        new ReconResult(id(5), Outcome.MISSING_IN_PAYMENTS));
    }

    @Test
    @DisplayName("REQ-PF-040 the same payment id twice in the payment statement is refused, not silently merged")
    void duplicateInPayments() {
        List<PaymentLine> twice = List.of(pay(1, 500, "COMPLETED"), pay(1, 500, "COMPLETED"));

        assertThatThrownBy(() -> StatementMatcher.match(twice, List.of(wal(1, 500, "CAPTURED"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(id(1).toString());
    }

    @Test
    @DisplayName("REQ-PF-040 the same payment id twice in the wallet statement is refused (a double capture must show)")
    void duplicateInWallet() {
        List<WalletLine> twice = List.of(wal(1, 500, "CAPTURED"), wal(1, 500, "CAPTURED"));

        assertThatThrownBy(() -> StatementMatcher.match(List.of(pay(1, 500, "COMPLETED")), twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(id(1).toString());
    }

    @Test
    @DisplayName("REQ-PF-040 a non-terminal payment state or an unknown wallet status is refused")
    void unknownVocabulary() {
        assertThatThrownBy(() -> StatementMatcher.match(List.of(pay(1, 5, "RESERVING")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatementMatcher.match(List.of(), List.of(wal(1, 5, "RESERVED"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
