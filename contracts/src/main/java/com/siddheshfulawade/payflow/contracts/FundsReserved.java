package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** The wallet holds the amount. */
public record FundsReserved(UUID paymentId) implements Reply {

    public FundsReserved {
        Objects.requireNonNull(paymentId, "paymentId");
    }
}
