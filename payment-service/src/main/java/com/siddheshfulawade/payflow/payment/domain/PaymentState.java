package com.siddheshfulawade.payflow.payment.domain;

/** Where a payment is in the saga (SPEC section 4). */
public enum PaymentState {
    RECEIVED(false),
    RESERVING(false),
    RISK_CHECKING(false),
    CAPTURING(false),
    COMPLETED(true),
    /** The hold is being returned to the payer. */
    RELEASING(false),
    REVERSED(true),
    REJECTED(true);

    private final boolean terminal;

    PaymentState(boolean terminal) {
        this.terminal = terminal;
    }

    /** A terminal payment never changes again. */
    public boolean terminal() {
        return terminal;
    }
}
