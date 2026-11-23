package com.siddheshfulawade.payflow.wallet.domain;

/** Only an ACTIVE wallet can reserve or be paid. Money already held can still be released or captured. */
public enum WalletStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
