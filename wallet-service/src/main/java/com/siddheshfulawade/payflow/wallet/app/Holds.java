package com.siddheshfulawade.payflow.wallet.app;

import com.siddheshfulawade.payflow.wallet.domain.Hold;
import com.siddheshfulawade.payflow.wallet.domain.HoldState;
import com.siddheshfulawade.payflow.wallet.domain.RejectReason;
import java.util.Optional;
import java.util.UUID;

/** The hold rows. Every method joins the caller's transaction. */
public interface Holds {

    /**
     * Inserts the payment's hold if there is none. True means this transaction makes the payment's decision: every
     * other transaction that inserts for this payment waits until this one ends, and nobody sees the row before it
     * commits.
     */
    boolean insertIfAbsent(UUID paymentId, String payer, long amount, HoldState state);

    /** The payment's committed hold, row-locked until the transaction ends. */
    Optional<Hold> lock(UUID paymentId);

    /** RESERVED to REJECTED; throws in any other state. */
    void reject(UUID paymentId, RejectReason reason);

    /** RESERVED to RELEASED; throws in any other state. */
    void release(UUID paymentId);

    /** RESERVED to CAPTURED, recording the payee; throws in any other state. */
    void capture(UUID paymentId, String payee);
}
