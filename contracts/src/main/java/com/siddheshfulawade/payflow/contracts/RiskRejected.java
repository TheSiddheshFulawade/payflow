package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** Risk refuses the payment; {@code rule} names the rule that refused it. */
public record RiskRejected(UUID paymentId, String rule) implements Reply {

    public RiskRejected {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.text(rule, "rule");
    }
}
