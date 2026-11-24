package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.contracts.Command;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentEvent;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.domain.Transition;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The only way a payment row changes: the guarded update and what goes with it, all in the caller's transaction
 * (REQ-PF-014, 020).
 *
 * <p>A transition writes row, audit event and next command. A re-send keeps the state and writes no event: a payment
 * enters each state once (a unique index on payment_event enforces it).
 */
final class TransitionWriter {

    private static final int FIRST_ATTEMPT = 1;

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
     * @param cause the reply that caused the transition; null when the API request or a timeout did
     * @throws StaleVersionException if another transaction moved the payment first
     */
    Payment write(Payment from, Transition transition, Envelope cause) {
        Instant now = now();
        Payment to = from.movedTo(transition.to(), deadline(transition.to(), FIRST_ATTEMPT, now), now);
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
        transition.command().ifPresent(c -> send(c, to));
        return to;
    }

    /**
     * The current step's command again: next attempt, new deadline, new message id (the inbox of the receiver would drop
     * the old one).
     *
     * @throws StaleVersionException if another transaction moved the payment first
     */
    Payment resend(Payment from, Command command) {
        Instant now = now();
        Payment to = from.nextAttempt(deadline(from.state(), from.stepAttempts() + 1, now), now);
        payments.update(from, to);
        send(command, to);
        return to;
    }

    /** Microseconds, as PostgreSQL stores them: a payment looks the same before and after it was stored. */
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Null in states that wait for nothing. */
    private Instant deadline(PaymentState state, int attempt, Instant now) {
        return deadlines.in(state, attempt).map(now::plus).orElse(null);
    }

    /** The outbox appends only inside a transaction, so a command never exists without its state change. */
    private void send(Command command, Payment payment) {
        outbox.append(Envelope.of(command, payment.stepAttempts(), clock));
    }
}
