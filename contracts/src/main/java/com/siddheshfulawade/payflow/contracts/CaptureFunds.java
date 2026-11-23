package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** Step 3: move the held {@code amount} from the payer to the payee's available balance. */
public record CaptureFunds(UUID paymentId, String payer, String payee, long amount, String currency)
        implements Command {

    public CaptureFunds {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.account(payer, "payer");
        Checks.account(payee, "payee");
        Checks.amount(amount);
        Checks.currency(currency);
    }
}
