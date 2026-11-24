package com.siddheshfulawade.payflow.payment.app;

import java.util.UUID;

/**
 * Another transaction moved the payment between our read and our update. Everything of this transaction rolls back
 * (inbox row too), so the message is redelivered and decided again on the new state.
 */
public class StaleVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleVersionException(UUID paymentId) {
        super("payment " + paymentId + " was changed by another transaction");
    }
}
