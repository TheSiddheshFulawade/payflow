package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage of payments and their audit events. Every write joins the caller's transaction. */
public interface Payments {

    /** Stores a new payment; false when the client already used this Idempotency-Key. */
    boolean insertIfAbsent(Payment payment);

    Optional<Payment> find(UUID id);

    Optional<Payment> findByKey(String clientId, String idempotencyKey);

    /**
     * Replaces {@code from} with {@code to}, only if the stored row still has {@code from}'s version (REQ-PF-014).
     *
     * @throws StaleVersionException if another transaction moved the payment first
     */
    void update(Payment from, Payment to);

    void addEvent(PaymentEvent event);

    /**
     * Payments whose deadline lies strictly before {@code now}, the longest overdue first. Takes no locks, so it is a
     * hint: only {@link #lockOverdue} decides.
     */
    List<UUID> overdueIds(Instant now, int limit);

    /**
     * Locks the payment until the caller's transaction ends, if it is (still) overdue. Empty when it moved on in the
     * meantime, and when another transaction holds it: that one is moving it, so there is nothing to wait for.
     */
    Optional<Payment> lockOverdue(UUID id, Instant now);
}
