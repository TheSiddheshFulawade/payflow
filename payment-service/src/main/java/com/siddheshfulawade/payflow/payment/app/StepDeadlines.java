package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.time.Duration;
import java.util.Optional;

/** How long each step may take before it is overdue (SPEC section 4). */
public record StepDeadlines(Duration reserve, Duration risk, Duration capture) {

    public StepDeadlines {
        positive(reserve, "reserve");
        positive(risk, "risk");
        positive(capture, "capture");
    }

    /** Empty for states that wait for nothing: they have no deadline. */
    Optional<Duration> in(PaymentState state) {
        return switch (state) {
            case RESERVING -> Optional.of(reserve);
            case RISK_CHECKING -> Optional.of(risk);
            case CAPTURING -> Optional.of(capture);
            case RELEASING -> throw new IllegalStateException("compensation arrives with PF-06");
            case RECEIVED, COMPLETED, REFUNDED, REJECTED -> Optional.empty();
        };
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("payflow.payment.deadlines." + name + " must be positive");
        }
    }
}
