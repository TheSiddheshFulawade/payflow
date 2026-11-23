package com.siddheshfulawade.payflow.wallet.domain;

import java.util.Optional;

/** A wallet as read under its row lock: a decision on it stays true until the transaction ends. */
public record Wallet(String id, long available, WalletStatus status) {

    /** Why {@code amount} cannot be held here, or empty if it can. */
    public Optional<RejectReason> refusesReserve(long amount) {
        if (status != WalletStatus.ACTIVE) {
            return Optional.of(RejectReason.WALLET_FROZEN);
        }
        // REQ-PF-030: available never goes negative (the CHECK in the database is the second guard)
        return available < amount ? Optional.of(RejectReason.INSUFFICIENT_FUNDS) : Optional.empty();
    }

    /** Why this wallet cannot be paid, or empty if it can. */
    public Optional<RejectReason> refusesCredit() {
        return status == WalletStatus.ACTIVE ? Optional.empty() : Optional.of(RejectReason.PAYEE_NOT_ACTIVE);
    }
}
