package com.siddheshfulawade.payflow.payment.api;

import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.time.Instant;
import java.util.UUID;

/** The public fields of a payment: no client id, key, hash, version or deadline. */
record PaymentView(
        UUID id,
        String payer,
        String payee,
        long amount,
        String currency,
        PaymentState state,
        Instant createdAt,
        Instant updatedAt) {

    static PaymentView of(Payment p) {
        return new PaymentView(
                p.id(), p.payer(), p.payee(), p.amount(), p.currency(), p.state(), p.createdAt(), p.updatedAt());
    }
}
