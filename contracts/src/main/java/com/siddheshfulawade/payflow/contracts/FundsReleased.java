package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** The hold was returned to the payer's available balance. */
public record FundsReleased(UUID paymentId) implements Reply {

    public FundsReleased {
        Objects.requireNonNull(paymentId, "paymentId");
    }
}
