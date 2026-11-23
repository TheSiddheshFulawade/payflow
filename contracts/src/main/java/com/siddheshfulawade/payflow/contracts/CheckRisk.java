package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** Step 2: ask risk whether the payer may send {@code amount}. */
public record CheckRisk(UUID paymentId, String payer, long amount, String currency) implements Command {

    public CheckRisk {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.account(payer, "payer");
        Checks.amount(amount);
        Checks.currency(currency);
    }
}
