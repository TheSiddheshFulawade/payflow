package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentEvent;
import com.siddheshfulawade.payflow.payment.domain.Transition;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The only way a payment changes state: row, audit event and next command, all in the caller's transaction
 * (REQ-PF-014, 020).
 *
 * <p>For real transitions only: a payment enters each state once (a unique index on payment_event enforces it).
 * Re-sending the current step's command keeps the state and writes no event.
 */
final class TransitionWriter {

    private final Payments payments;
    private final OutboxWriter outbox;
    private final StepDeadlines deadlines;
    private final Clock clock;

    TransitionWriter(Payments payments, OutboxWriter outbox, StepDeadlines deadlines, Clock clock) {
        this.payments = payments;
        this.outbox = outbox;
        this.deadlines = deadlines;
        this.clock = clock;
    }

    /**
     * @param cause the reply that caused the transition; null when the API request did
     * @throws StaleVersionException if another transaction moved the payment first
     */
    Payment write(Payment from, Transition transition, Envelope cause) {
        Instant now = now();
        Instant deadline = deadlines.in(transition.to()).map(now::plus).orElse(null);
        Payment to = from.movedTo(transition.to(), deadline, now);
        // the guarded update comes first: whoever loses the race stops here, before any event or command
        payments.update(from, to);
        payments.addEvent(new PaymentEvent(
                to.id(),
                from.state(),
                to.state(),
                cause == null ? null : cause.messageId(),
                cause == null ? null : cause.attempt(),
                transition.reason(),
                now));
        // the outbox appends only inside a transaction, so a command never exists without its state change
        transition.command().ifPresent(c -> outbox.append(Envelope.of(c, to.stepAttempts(), clock)));
        return to;
    }

    /** Microseconds, as PostgreSQL stores them: a payment looks the same before and after it was stored. */
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
