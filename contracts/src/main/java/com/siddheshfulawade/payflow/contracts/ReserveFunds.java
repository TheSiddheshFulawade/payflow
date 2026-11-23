package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** Step 1: hold {@code amount} of the payer's available balance. */
public record ReserveFunds(UUID paymentId, String payer, long amount, String currency) implements Command {

    public ReserveFunds {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.account(payer, "payer");
        Checks.amount(amount);
        Checks.currency(currency);
    }
}
