package com.siddheshfulawade.payflow.payment.support;

import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.time.Instant;
import java.util.UUID;

/** Plain payment rows for the pure tests (no database, no Spring). */
public final class PaymentFixtures {

    public static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private PaymentFixtures() {}

    /** A payment in {@code state} on the given step attempt; the deadline follows the rule of the schema. */
    public static Payment payment(PaymentState state, int stepAttempts) {
        boolean hasDeadline = state != PaymentState.RECEIVED && !state.terminal();
        return new Payment(
                UUID.randomUUID(),
                "local",
                "key-1",
                "0".repeat(64),
                "alice",
                "bob",
                2_500,
                "INR",
                state,
                stepAttempts,
                hasDeadline ? NOW.plusSeconds(10) : null,
                3,
                NOW,
                NOW);
    }

    public static Payment payment(PaymentState state) {
        return payment(state, state == PaymentState.RECEIVED ? 0 : 1);
    }
}
