package com.siddheshfulawade.payflow.wallet.domain;

/** Why the wallet refused a command. The names travel in the replies and are stored with a REJECTED hold. */
public enum RejectReason {
    WALLET_UNKNOWN,
    /** The payer's wallet is not ACTIVE (frozen or closed). */
    WALLET_FROZEN,
    INSUFFICIENT_FUNDS,
    PAYEE_UNKNOWN,
    PAYEE_NOT_ACTIVE,
    /** The payment holds no money: never reserved, rejected, or already released. */
    NO_ACTIVE_HOLD
}
