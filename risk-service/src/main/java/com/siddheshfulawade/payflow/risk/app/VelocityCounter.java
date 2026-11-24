package com.siddheshfulawade.payflow.risk.app;

import java.time.Instant;
import java.util.UUID;

/** Counts a payer's recent payments, shared by all instances of the service. */
public interface VelocityCounter {

    /**
     * Counts the payment if the payer is within the limit. Asking again for a counted payment is true and free.
     *
     * @throws VelocityUnavailableException if the count is unknown; the caller must not approve
     */
    boolean allow(String payer, UUID paymentId, Instant now);
}
