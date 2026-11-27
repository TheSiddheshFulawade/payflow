package com.siddheshfulawade.payflow.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The pure decisions, without a database: what a hold answers, and when a wallet refuses. */
class WalletDecisionsTest {

    private final UUID payment = UUID.randomUUID();

    private Hold hold(HoldState state) {
        return new Hold(
                payment,
                "alice",
                300,
                state,
                state == HoldState.REJECTED ? RejectReason.INSUFFICIENT_FUNDS : null,
                state == HoldState.CAPTURED ? "bob" : null);
    }

    private ReserveFunds reserve(String payer, long amount) {
        return new ReserveFunds(payment, payer, amount, "INR", "REQ");
    }

    private CaptureFunds capture(String payer, long amount) {
        return capture(payer, "bob", amount);
    }

    private CaptureFunds capture(String payer, String payee, long amount) {
        return new CaptureFunds(payment, payer, payee, amount, "INR");
    }

    private Reply rejected(String reason) {
        return new FundsRejected(payment, reason);
    }

    private Reply captureFailed(String reason) {
        return new CaptureFailed(payment, reason);
    }

    // ---- an existing hold answers a re-sent reserve ----

    @ParameterizedTest
    @EnumSource(names = {"RESERVED", "CAPTURED"})
    @DisplayName("REQ-PF-021 a hold that was made answers FundsReserved; other money is a poison message")
    void reserveOnMadeHold(HoldState state) {
        assertThat(hold(state).answerToReserve(reserve("alice", 300))).isEqualTo(new FundsReserved(payment));
        assertThatThrownBy(() -> hold(state).answerToReserve(reserve("alice", 301)))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain("alice", "301", "300");
        assertThatThrownBy(() -> hold(state).answerToReserve(reserve("carol", 300)))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain("carol", "alice", "300");
    }

    @Test
    @DisplayName("REQ-PF-021 a rejected hold repeats its reason and a released one is NO_ACTIVE_HOLD, for any amount")
    void reserveOnFinalHold() {
        assertThat(hold(HoldState.REJECTED).answerToReserve(reserve("alice", 1)))
                .isEqualTo(rejected("INSUFFICIENT_FUNDS"));
        assertThat(hold(HoldState.RELEASED).answerToReserve(reserve("alice", 300)))
                .isEqualTo(rejected("NO_ACTIVE_HOLD"));
    }

    // ---- release ----

    @Test
    @DisplayName("REQ-PF-030 only a RESERVED hold has money to release; CAPTURED answers with the truth")
    void release() {
        assertThat(hold(HoldState.RESERVED).answerToRelease()).isEmpty();
        assertThat(hold(HoldState.RELEASED).answerToRelease()).contains(new FundsReleased(payment));
        assertThat(hold(HoldState.REJECTED).answerToRelease()).contains(new FundsReleased(payment));
        assertThat(hold(HoldState.CAPTURED).answerToRelease()).contains(new FundsCaptured(payment));
    }

    // ---- capture ----

    @Test
    @DisplayName("REQ-PF-030 only a matching RESERVED hold can be captured; other money is a poison message")
    void captureReserved() {
        assertThat(hold(HoldState.RESERVED).answerToCapture(capture("alice", 300)))
                .isEmpty();
        assertThatThrownBy(() -> hold(HoldState.RESERVED).answerToCapture(capture("alice", 299)))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain("alice", "299", "300");
        assertThatThrownBy(() -> hold(HoldState.RESERVED).answerToCapture(capture("carol", 300)))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain("carol", "300");
    }

    @Test
    @DisplayName("REQ-PF-021 a CAPTURED hold answers FundsCaptured again, only for the money and payee it captured")
    void captureCaptured() {
        assertThat(hold(HoldState.CAPTURED).answerToCapture(capture("alice", 300)))
                .contains(new FundsCaptured(payment));
        assertThatThrownBy(() -> hold(HoldState.CAPTURED).answerToCapture(capture("alice", 301)))
                .isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> hold(HoldState.CAPTURED).answerToCapture(capture("carol", 300)))
                .isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> hold(HoldState.CAPTURED).answerToCapture(capture("alice", "dave", 300)))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain("dave", "bob", "alice");
    }

    @Test
    @DisplayName("REQ-PF-021 a RESERVED hold has no payee yet, so any payee is decided by the wallets")
    void captureReservedAnyPayee() {
        assertThat(hold(HoldState.RESERVED).answerToCapture(capture("alice", "dave", 300)))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(names = {"RELEASED", "REJECTED"})
    @DisplayName("REQ-PF-030 a released or rejected hold can never be captured")
    void captureFinal(HoldState state) {
        assertThat(hold(state).answerToCapture(capture("alice", 300))).contains(captureFailed("NO_ACTIVE_HOLD"));
    }

    // ---- wallets ----

    @Test
    @DisplayName("REQ-PF-030 a wallet can reserve up to exactly its available balance")
    void reserveBounds() {
        Wallet wallet = new Wallet("alice", 300, WalletStatus.ACTIVE);

        assertThat(wallet.refusesReserve(300)).isEmpty();
        assertThat(wallet.refusesReserve(301)).contains(RejectReason.INSUFFICIENT_FUNDS);
    }

    @ParameterizedTest
    @EnumSource(
            value = WalletStatus.class,
            names = {"FROZEN", "CLOSED"})
    @DisplayName("REQ-PF-030 a wallet that is not ACTIVE neither reserves nor receives")
    void inactive(WalletStatus status) {
        Wallet wallet = new Wallet("alice", 300, status);

        assertThat(wallet.refusesReserve(1)).contains(RejectReason.WALLET_FROZEN);
        assertThat(wallet.refusesCredit()).contains(RejectReason.PAYEE_NOT_ACTIVE);
        assertThat(new Wallet("alice", 0, WalletStatus.ACTIVE).refusesCredit()).isEmpty();
    }
}
