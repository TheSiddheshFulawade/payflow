package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.domain.Overdue;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.domain.SagaStateMachine;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves payments whose step got no reply in time: re-sends the command, or gives the step up and releases the hold
 * (REQ-PF-012). Only a published command can be late: a payment whose command is still unsent in the outbox is
 * postponed. No payment is forgotten, because every state that waits has a deadline (a CHECK enforces it) and every
 * sweep sets the next one.
 *
 * <p>One transaction per payment, so a failing payment rolls back alone. Re-sending is safe because wallet and risk are
 * idempotent per payment: a command that did run is answered again, with the attempt of the re-send.
 *
 * <p>Failures considered:
 *
 * <ul>
 *   <li>crash before the commit: attempt, deadline and command all roll back; the payment is still overdue and the next
 *       sweep, on any instance, takes it
 *   <li>crash after the commit: the command is in the outbox and the new deadline in the row; the relay sends the
 *       command (again), and if that gets no reply either, the new deadline passes
 *   <li>two sweepers at once: {@code for update skip locked} lets one have the payment; the other skips it, or finds
 *       under the lock that its deadline has moved
 *   <li>sweeper against a reply: the reply's update and our lock exclude each other. If the reply commits first, the
 *       payment is not overdue any more. If we do, the reply's guarded update finds another version, rolls back, is
 *       redelivered and decided again: an old attempt by then, unless we only postponed, or it ends a release
 *   <li>reply to an old attempt: ignored, except the wallet's final word while RELEASING, see
 *       {@link SagaStateMachine#on}. Nothing is lost: the re-sent command gets its own answer, from the same decision
 *   <li>reply or command parked on the dead-letter topic: the same as a lost one, the re-send gets a new answer
 *   <li>broker down, or the relay slow or stopped: the command is still unsent in the outbox, so the payment is only
 *       postponed by its current wait. No attempt is used up, nothing piles up behind the command, and no payment is
 *       released while its command is unsent. A reply is still handled at any time. The deadline is not counted from
 *       the publish: a command published just before it gets a short wait, so it is re-sent early or, on the last
 *       attempt, its payment is released early. Both are safe
 *   <li>an instance frozen or cut off inside a transaction: it keeps its payment or outbox rows locked, so sweeps skip
 *       the payment, or postpone it because its command is unsent. PostgreSQL ends the idle session after 60 s
 *       (application.yml), which rolls back and unlocks; the next sweep or relay run, on any instance, takes the rows
 *   <li>a command the broker never takes (topic missing or not writable): its payment is postponed until that is
 *       repaired, with the warning of {@link #sweep()} and the relay's own. A dead peer is not such a case: it ends
 *       after the 60 s above. The hold is kept that long, even if only the risk topic is at fault and a release could
 *       reach the wallet: "unsent" does not tell us why. This postponement is our reading of REQ-PF-012: a step's
 *       retries count sends that left, not sends that wait in the outbox
 *   <li>a release that is never answered: re-sent forever at the capped interval, with a warning past attempt
 *       {@value #WARN_AFTER_RELEASE_ATTEMPT}. It never ends by itself, so a payment can't end REVERSED while money is
 *       held
 *   <li>a payment that fails every time: logged on every sweep, and the others go on. As the longest overdue it keeps a
 *       place in every batch, so only a full batch of such payments could hold up the rest
 * </ul>
 */
public class PaymentSweeper {

    private static final int WARN_AFTER_RELEASE_ATTEMPT = 3;

    private static final Logger log = LoggerFactory.getLogger(PaymentSweeper.class);

    private final Payments payments;
    private final TransitionWriter transitions;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final int maxResends;

    public PaymentSweeper(
            Payments payments,
            OutboxWriter outbox,
            StepDeadlines deadlines,
            Clock clock,
            PlatformTransactionManager transactionManager,
            int batchSize,
            int maxResends) {
        this.payments = payments;
        this.transitions = new TransitionWriter(payments, outbox, deadlines, clock);
        this.transaction = new TransactionTemplate(transactionManager);
        // Stated because lockOverdue needs it: a row that changed since the statement began is checked again in its
        // newest version. Under REPEATABLE READ it would fail instead.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.batchSize = batchSize;
        this.maxResends = maxResends;
    }

    /**
     * One pass over at most {@code batchSize} overdue payments, by the application's clock (which also set their
     * deadlines), not the database's.
     *
     * @return how many payments were re-sent or compensated. A postponed payment is not counted: nothing was sent for
     *     it. Neither is one that was skipped or failed.
     */
    public int sweep() {
        Instant now = transitions.now();
        int moved = 0;
        int postponed = 0;
        for (UUID id : payments.overdueIds(now, batchSize)) {
            try {
                Optional<Swept> swept = sweep(id, now);
                if (swept.isEmpty()) {
                    continue;
                }
                if (swept.get().postponed()) {
                    postponed++;
                } else {
                    moved++;
                    logMoved(swept.get().payment());
                }
            } catch (RuntimeException e) {
                logFailed(id, e);
            }
        }
        if (postponed > 0) {
            // one line per sweep, not per payment: in an outage every in-flight payment is postponed
            log.warn("{} overdue payments were postponed: their commands are not published yet", postponed);
        }
        return moved;
    }

    private record Swept(Payment payment, boolean postponed) {}

    /** Row, event (if any) and command commit together or not at all (REQ-PF-020). */
    private Optional<Swept> sweep(UUID id, Instant now) {
        return transaction.execute(status -> payments.lockOverdue(id, now).map(this::sweep));
    }

    private Swept sweep(Payment payment) {
        // Before the decision to re-send or compensate: an unpublished command has no reply to wait for, so it used up
        // nothing, and another command would only queue up behind it.
        if (payments.hasUnsentCommand(payment.id())) {
            return new Swept(transitions.postpone(payment), true);
        }
        Payment moved = switch (SagaStateMachine.overdue(payment, maxResends)) {
            case Overdue.Resend resend -> transitions.resend(payment, resend.command());
            case Overdue.Compensate compensate -> transitions.write(payment, compensate.transition(), null);
        };
        return new Swept(moved, false);
    }

    /** Ids, states and attempts only: account ids stay out of the log. */
    private static void logMoved(Payment payment) {
        if (payment.state() == PaymentState.RELEASING && payment.stepAttempts() > WARN_AFTER_RELEASE_ATTEMPT) {
            log.warn(
                    "payment {} still waits for its release: attempt {} sent, money may be held",
                    payment.id(),
                    payment.stepAttempts());
        } else {
            log.info(
                    "payment {} was overdue: now {} attempt {}", payment.id(), payment.state(), payment.stepAttempts());
        }
    }

    /** Exception types only: the texts come from libraries and may quote row values. */
    private static void logFailed(UUID id, RuntimeException e) {
        log.warn(
                "payment {} could not be swept, the next sweep tries again: {} caused by {}",
                id,
                e.getClass().getName(),
                NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());
    }
}
