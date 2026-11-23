package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** Risk allows the payment. */
public record RiskApproved(UUID paymentId) implements Reply {

    public RiskApproved {
        Objects.requireNonNull(paymentId, "paymentId");
    }
}
