package com.siddheshfulawade.payflow.risk.app;

import com.siddheshfulawade.payflow.risk.domain.RiskDecision;
import com.siddheshfulawade.payflow.risk.domain.RiskRule;
import java.util.Optional;
import java.util.UUID;

/** The decision rows. Every method joins the caller's transaction. */
public interface RiskDecisions {

    /**
     * Inserts the payment's PENDING decision if there is none. True means this transaction decides the payment: every
     * other transaction that claims it waits until this one ends, and nobody sees the row before it commits.
     */
    boolean claim(UUID paymentId, String payer, long amount);

    /** The payment's committed decision. */
    Optional<RiskDecision> find(UUID paymentId);

    /** PENDING to APPROVED; throws in any other state. */
    void approve(UUID paymentId);

    /** PENDING to REJECTED; throws in any other state. */
    void reject(UUID paymentId, RiskRule rule);
}
