package com.siddheshfulawade.payflow.payment.api;

import com.siddheshfulawade.payflow.payment.app.NewPayment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Asks for a payment (REQ-PF-004).
 *
 * @param payer same alphabet as wallet ids (payee too)
 * @param amount minor units; boxed, so a missing amount is an error and not 0
 */
@DifferentParties
record CreatePaymentRequest(
        @NotNull @Pattern(regexp = CreatePaymentRequest.WALLET_ID)
        String payer,

        @NotNull @Pattern(regexp = CreatePaymentRequest.WALLET_ID)
        String payee,

        @NotNull @Min(1) @Max(10_000_000) Long amount,
        @NotNull @Pattern(regexp = "INR") String currency) {

    static final String WALLET_ID = "[A-Za-z0-9][A-Za-z0-9._@-]{0,63}";

    NewPayment toNewPayment() {
        return new NewPayment(payer, payee, amount, currency);
    }
}
