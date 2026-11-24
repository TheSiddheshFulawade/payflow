package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentEvent;
import com.siddheshfulawade.payflow.payment.domain.SagaStateMachine;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts payments, at most one per client and Idempotency-Key (REQ-PF-001, 002).
 *
 * <p>Failures considered:
 *
 * <ul>
 *   <li>crash before the commit: payment, events and ReserveFunds all roll back; the key is not used up, and the
 *       client's retry creates the payment
 *   <li>crash after the commit, before the response: the client's retry gets the original payment
 *   <li>crash before or after ReserveFunds is published: it is in the outbox; the relay sends it (again)
 *   <li>duplicate request: the same body gets the original payment, another body is refused; nothing is written
 *   <li>the same key twice at once, on one or two instances: the unique constraint lets one insert win; the other
 *       waits for it and then behaves like a duplicate (or creates the payment, if the first rolled back)
 *   <li>the winner hangs: the waiting insert ends with lock_timeout (an error, nothing written); retrying is safe
 *   <li>late: a retry long after the payment moved on gets its current state; keys are never reused or expired
 * </ul>
 */
@Service
public class PaymentService {

    /** @param isNew false when this is the payment of an earlier request with the same key and body */
    public record Created(Payment payment, boolean isNew) {}

    private final Payments payments;
    private final TransitionWriter transitions;

    public PaymentService(Payments payments, OutboxWriter outbox, StepDeadlines deadlines, Clock clock) {
        this.payments = payments;
        this.transitions = new TransitionWriter(payments, outbox, deadlines, clock);
    }

    /**
     * Payment row, its first two events and ReserveFunds commit together or not at all (REQ-PF-020).
     *
     * <p>READ COMMITTED, stated because the conflict path needs it: each statement sees what was committed before it
     * started, so after the insert has waited for a concurrent creator, the next select sees the creator's row.
     * Under REPEATABLE READ the insert would fail instead.
     *
     * @throws IdempotencyKeyReusedException if the client used the key with another body
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created create(String clientId, String idempotencyKey, NewPayment request) {
        String requestHash = request.requestHash();
        Instant now = transitions.now();
        Payment received = Payment.received(
                UUID.randomUUID(),
                clientId,
                idempotencyKey,
                requestHash,
                request.payer(),
                request.payee(),
                request.amount(),
                request.currency(),
                now);
        if (!payments.insertIfAbsent(received)) {
            return new Created(original(clientId, idempotencyKey, requestHash), false);
        }
        payments.addEvent(new PaymentEvent(received.id(), null, received.state(), null, null, null, now));
        return new Created(transitions.write(received, SagaStateMachine.start(received), null), true);
    }

    public Optional<Payment> find(UUID id) {
        return payments.find(id);
    }

    /** The payment that stopped our insert. Payments are never deleted, so it must be there. */
    private Payment original(String clientId, String idempotencyKey, String requestHash) {
        Payment original = payments.findByKey(clientId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("the payment of a used Idempotency-Key disappeared"));
        if (!original.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException("the Idempotency-Key was used with a different body");
        }
        return original;
    }
}
