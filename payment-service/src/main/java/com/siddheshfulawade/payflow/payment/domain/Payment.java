package com.siddheshfulawade.payflow.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One payment row.
 *
 * @param requestHash hash of the request that created it (REQ-PF-002)
 * @param amount minor units
 * @param stepAttempts attempt of the current step; only a reply with this attempt counts
 * @param deadlineAt when the current step is overdue; null in RECEIVED and in terminal states
 * @param version optimistic lock: grows by 1 with every transition (REQ-PF-014)
 */
public record Payment(
        UUID id,
        String clientId,
        String idempotencyKey,
        String requestHash,
        String payer,
        String payee,
        long amount,
        String currency,
        PaymentState state,
        int stepAttempts,
        Instant deadlineAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** A payment that was just accepted; no step has started. */
    public static Payment received(
            UUID id,
            String clientId,
            String idempotencyKey,
            String requestHash,
            String payer,
            String payee,
            long amount,
            String currency,
            Instant now) {
        return new Payment(
                id,
                clientId,
                idempotencyKey,
                requestHash,
                payer,
                payee,
                amount,
                currency,
                PaymentState.RECEIVED,
                0,
                null,
                0,
                now,
                now);
    }

    /** This payment in its next state. A new step starts at attempt 1; a terminal state starts no step. */
    public Payment movedTo(PaymentState to, Instant deadline, Instant now) {
        return new Payment(
                id,
                clientId,
                idempotencyKey,
                requestHash,
                payer,
                payee,
                amount,
                currency,
                to,
                to.terminal() ? stepAttempts : 1,
                deadline,
                version + 1,
                createdAt,
                now);
    }
}
