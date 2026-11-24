package com.siddheshfulawade.payflow.payment.app;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PaymentNotFoundException(UUID id) {
        super("payment " + id + " not found");
    }
}
