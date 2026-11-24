package com.siddheshfulawade.payflow.payment.domain;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import java.util.Optional;

/**
 * The saga table of SPEC section 4, as pure functions.
 *
 * <p>Commands are built from the payment row only, never from a reply: a wrong or forged reply can't change who pays
 * whom how much.
 */
public final class SagaStateMachine {

    /** The first step of a RECEIVED payment. */
    public static Transition start(Payment payment) {
        return Transition.step(
                PaymentState.RESERVING,
                new ReserveFunds(payment.id(), payment.payer(), payment.amount(), payment.currency()));
    }

    /**
     * What a reply does to the payment; empty when it has no transition.
     *
     * <p>Invariant (REQ-PF-010, 013): a reply acts only on a payment that waits for exactly this reply, on exactly
     * this attempt. Everything else (late, duplicate, another attempt, terminal payment) changes nothing.
     */
    public static Optional<Transition> on(Payment payment, Reply reply, int replyAttempt) {
        if (payment.state().terminal() || replyAttempt != payment.stepAttempts()) {
            return Optional.empty();
        }
        // spelled out, no default: a new reply type does not compile until it is decided here
        return switch (reply) {
            case FundsReserved r ->
                onlyIn(
                        PaymentState.RESERVING,
                        payment,
                        Transition.step(PaymentState.RISK_CHECKING, checkRisk(payment)));
            // nothing is held yet, so there is nothing to compensate
            case FundsRejected r ->
                onlyIn(PaymentState.RESERVING, payment, Transition.end(PaymentState.REJECTED, r.reason()));
            case RiskApproved r ->
                onlyIn(
                        PaymentState.RISK_CHECKING,
                        payment,
                        Transition.step(PaymentState.CAPTURING, captureFunds(payment)));
            case FundsCaptured r ->
                onlyIn(PaymentState.CAPTURING, payment, Transition.end(PaymentState.COMPLETED, null));
            // compensation arrives with PF-06; until then see needsCompensation
            case RiskRejected r -> Optional.empty();
            case CaptureFailed r -> Optional.empty();
            case FundsReleased r -> Optional.empty();
        };
    }

    /**
     * Whether this reply means "release the hold". Until PF-06 {@link #on} has no transition for it, but the caller
     * must not ignore it like a late reply: the payer's money is held.
     */
    public static boolean needsCompensation(Payment payment, Reply reply, int replyAttempt) {
        boolean failedStep = (payment.state() == PaymentState.RISK_CHECKING && reply instanceof RiskRejected)
                || (payment.state() == PaymentState.CAPTURING && reply instanceof CaptureFailed);
        return failedStep && replyAttempt == payment.stepAttempts();
    }

    private static Optional<Transition> onlyIn(PaymentState expected, Payment payment, Transition transition) {
        return payment.state() == expected ? Optional.of(transition) : Optional.empty();
    }

    private static CheckRisk checkRisk(Payment p) {
        return new CheckRisk(p.id(), p.payer(), p.amount(), p.currency());
    }

    private static CaptureFunds captureFunds(Payment p) {
        return new CaptureFunds(p.id(), p.payer(), p.payee(), p.amount(), p.currency());
    }

    private SagaStateMachine() {}
}
