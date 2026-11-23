package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** Compensation: return the held {@code amount} to the payer's available balance. */
public record ReleaseFunds(UUID paymentId, String payer, long amount) implements Command {

    public ReleaseFunds {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.account(payer, "payer");
        Checks.amount(amount);
    }
}
