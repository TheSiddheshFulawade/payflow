package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** The held amount was moved to the payee. */
public record FundsCaptured(UUID paymentId) implements Reply {

    public FundsCaptured {
        Objects.requireNonNull(paymentId, "paymentId");
    }
}
