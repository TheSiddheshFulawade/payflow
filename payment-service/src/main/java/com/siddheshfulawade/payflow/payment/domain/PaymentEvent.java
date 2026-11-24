package com.siddheshfulawade.payflow.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit of one transition.
 *
 * @param from null for the first event
 * @param messageId the reply that caused it; null when the API request or a timeout did
 * @param attempt that reply's attempt; null when no reply caused it
 * @param reason why the payment failed; null for a successful step
 */
public record PaymentEvent(
        UUID paymentId,
        PaymentState from,
        PaymentState to,
        UUID messageId,
        Integer attempt,
        String reason,
        Instant createdAt) {}
