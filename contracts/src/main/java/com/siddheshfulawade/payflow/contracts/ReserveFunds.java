package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/**
 * Step 1: hold {@code amount} of the payer's available balance.
 *
 * <p>{@code requester} is the JWT subject of the client who created the payment. The wallet holds money only if the
 * payer wallet belongs to the requester (REQ-PF-003), so it is required: a command without one is never an unchecked
 * reserve.
 */
public record ReserveFunds(UUID paymentId, String payer, long amount, String currency, String requester)
        implements Command {

    public ReserveFunds {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.account(payer, "payer");
        Checks.amount(amount);
        Checks.currency(currency);
        Checks.subject(requester, "requester");
    }
}
