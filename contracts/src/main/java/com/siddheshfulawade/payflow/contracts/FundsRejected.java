package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** The wallet could not hold the amount (for example insufficient funds, frozen or unknown wallet). */
public record FundsRejected(UUID paymentId, String reason) implements Reply {

    public FundsRejected {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.text(reason, "reason");
    }
}
