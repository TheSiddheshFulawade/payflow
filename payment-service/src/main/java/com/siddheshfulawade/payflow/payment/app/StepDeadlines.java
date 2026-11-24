package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.time.Duration;
import java.util.Optional;

/**
 * How long each step may take before it is overdue (SPEC section 4). The wait doubles with every attempt, so a service
 * that is down or slow is not flooded with re-sends.
 *
 * @param cap no attempt waits longer than this
 */
public record StepDeadlines(Duration reserve, Duration risk, Duration capture, Duration release, Duration cap) {

    public StepDeadlines {
        positive(reserve, "reserve");
        positive(risk, "risk");
        positive(capture, "capture");
        positive(release, "release");
        positive(cap, "cap");
    }

    /** min(base x 2^(attempt - 1), cap). Empty for states that wait for nothing: they have no deadline. */
    Optional<Duration> in(PaymentState state, int attempt) {
        return switch (state) {
            case RESERVING -> Optional.of(backoff(reserve, attempt));
            case RISK_CHECKING -> Optional.of(backoff(risk, attempt));
            case CAPTURING -> Optional.of(backoff(capture, attempt));
            case RELEASING -> Optional.of(backoff(release, attempt));
            case RECEIVED, COMPLETED, REVERSED, REJECTED -> Optional.empty();
        };
    }

    /** Stops at the cap instead of computing 2^attempt: a release may reach any attempt number. */
    private Duration backoff(Duration base, int attempt) {
        Duration wait = base;
        for (int i = 1; i < attempt && wait.compareTo(cap) < 0; i++) {
            // wait >= cap - wait means "doubled, it reaches the cap", without an overflow
            wait = wait.compareTo(cap.minus(wait)) >= 0 ? cap : wait.plus(wait);
        }
        return wait.compareTo(cap) < 0 ? wait : cap;
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("payflow.payment.deadlines." + name + " must be positive");
        }
    }
}
