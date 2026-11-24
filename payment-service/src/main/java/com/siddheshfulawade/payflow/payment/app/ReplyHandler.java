package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.contracts.Command;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.SagaStateMachine;
import com.siddheshfulawade.payflow.payment.domain.Transition;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The effect of a reply: moves the payment one step, forward or into its release, and queues the next command
 * (REQ-PF-010, 011, 013, 014).
 *
 * <p>Failures considered:
 *
 * <ul>
 *   <li>crash before the commit: inbox row, state, event and command all roll back; Kafka redelivers
 *   <li>crash after the commit, before the offset commit: redelivered; the inbox makes it a no-op
 *   <li>crash before or after the next command is published: it is in the outbox; the relay sends it (again)
 *   <li>duplicate (same message id): the inbox drops it; nothing is re-sent, see {@link #handle}
 *   <li>the same reply re-sent under a new message id, a late reply, another attempt, a finished payment: no
 *       transition exists, so it is ignored; its inbox row stays
 *   <li>two instances on one payment, or the sweeper: both may read the same version, one update wins, the other finds
 *       0 rows, rolls back completely and decides again on the new state when it is redelivered
 *   <li>lost or parked reply: the step's deadline passes; the {@link PaymentSweeper} re-sends the command
 *   <li>the same Idempotency-Key concurrently: decided by {@link PaymentService}; replies only ever see one payment
 * </ul>
 */
@Service
public class ReplyHandler {

    private static final Logger log = LoggerFactory.getLogger(ReplyHandler.class);

    private final Payments payments;
    private final TransitionWriter transitions;

    public ReplyHandler(Payments payments, OutboxWriter outbox, StepDeadlines deadlines, Clock clock) {
        this.payments = payments;
        this.transitions = new TransitionWriter(payments, outbox, deadlines, clock);
    }

    /**
     * MANDATORY: the effect must commit or roll back with the inbox row, so it runs only inside the idempotent
     * consumer's transaction.
     *
     * <p>Always returns empty, and appends the next command itself. A returned message becomes the inbox row's stored
     * reply, which a duplicate re-sends. Here that message would be the next command: a duplicate reply would re-send
     * the command, the wallet would answer the duplicate command with its stored reply, that duplicate reply would
     * re-send the command again, and the two services would loop forever.
     *
     * @throws PoisonMessageException if the message is not a reply, or its payment does not exist
     * @throws StaleVersionException if another transaction moved the payment first (the message is redelivered)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Envelope> handle(Envelope message) {
        Reply reply = switch (message.payload()) {
            case Reply r -> r;
            case Command c -> throw new PoisonMessageException("not a reply");
        };
        // a command leaves only after its payment committed, so no genuine reply can be earlier than the row
        Payment payment = payments.find(message.paymentId())
                .orElseThrow(() -> new PoisonMessageException("reply for an unknown payment"));
        Optional<Transition> transition = SagaStateMachine.on(payment, reply, message.attempt());
        if (transition.isPresent()) {
            transitions.write(payment, transition.get(), message);
        } else {
            logIgnored(payment, message);
        }
        return Optional.empty();
    }

    /** Ids, types and states only: account ids and reasons stay out of the log. */
    private static void logIgnored(Payment payment, Envelope message) {
        log.info(
                "ignored {} {} attempt {} for payment {}: it is {} on attempt {}",
                message.type(),
                message.messageId(),
                message.attempt(),
                payment.id(),
                payment.state(),
                payment.stepAttempts());
    }
}
