package com.siddheshfulawade.payflow.payment.app;

/** Who is calling the API; it scopes the Idempotency-Key. Never a value the caller can simply claim. */
public interface ClientIdentity {

    String clientId();
}
