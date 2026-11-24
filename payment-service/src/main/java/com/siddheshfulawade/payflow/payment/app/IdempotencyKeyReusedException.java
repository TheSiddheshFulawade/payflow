package com.siddheshfulawade.payflow.payment.app;

/** The client used this Idempotency-Key before, with a different request body (REQ-PF-002). */
public class IdempotencyKeyReusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
