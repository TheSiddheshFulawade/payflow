package com.siddheshfulawade.payflow.recon.domain;

/** How one payment id compares across the payment and the wallet statement (REQ-PF-040). */
public enum Outcome {
    MATCHED,
    MISSING_IN_WALLET,
    MISSING_IN_PAYMENTS,
    AMOUNT_MISMATCH,
    STATUS_MISMATCH
}
