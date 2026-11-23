package com.siddheshfulawade.payflow.wallet.domain;

/** Life of a payment's hold. Only RESERVED can change; the other states are final. */
public enum HoldState {
    /** The amount sits in the payer's held balance. */
    RESERVED,
    /** Nothing is held and nothing ever will be: given back, or released before the reserve arrived (tombstone). */
    RELEASED,
    /** The amount went to the payee. */
    CAPTURED,
    /** The reserve was refused; nothing was ever held. */
    REJECTED
}
