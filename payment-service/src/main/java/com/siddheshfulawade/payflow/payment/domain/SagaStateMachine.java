package com.siddheshfulawade.payflow.payment.domain;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Command;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import java.util.Optional;
import java.util.Set;

/**
 * The saga table of SPEC section 4, as pure functions.
 *
 * <p>Commands are built from the payment row only, never from a reply: a wrong or forged reply can't change who pays
 * whom how much, and a re-sent command is the same as the first one.
 */
public final class SagaStateMachine {

    /** Why a payment that got no reply was released. */
    static final String TIMEOUT = "TIMEOUT";

    /** The first step of a RECEIVED payment. */
    public static Transition start(Payment payment) {
        return Transition.step(PaymentState.RESERVING, reserveFunds(payment));
    }

    /**
     * What a reply does to the payment; empty when it has no transition.
     *
     * <p>Invariant (REQ-PF-010, 013): a reply acts only on a payment that waits for exactly this reply, on exactly
     * this attempt. Everything else (late, duplicate, another attempt, terminal payment) changes nothing.
     *
     * <p>The one exception is the attempt while RELEASING. Both replies that end a release, FundsReleased and
     * FundsCaptured, state a final fact of the hold: one that is released, rejected or captured never changes again, so
     * an old answer is as true as a new one. It must count, or a wallet that answers more slowly than the release is
     * re-sent would never end the payment: every answer would belong to an older attempt.
     */
    public static Optional<Transition> on(Payment payment, Reply reply, int replyAttempt) {
        if (payment.state().terminal()) {
            return Optional.empty();
        }
        // a new transition out of RELEASING must also be a final fact of the hold, or be checked against the attempt
        if (payment.state() != PaymentState.RELEASING && replyAttempt != payment.stepAttempts()) {
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
            case RiskRejected r -> onlyIn(PaymentState.RISK_CHECKING, payment, release(payment, r.rule()));
            // While RELEASING it means "already captured": the payee has the money, so the payment is COMPLETED, never
            // REVERSED. It may be the answer to the release or a late answer to the capture: both say the same.
            case FundsCaptured r ->
                onlyIn(
                        Set.of(PaymentState.CAPTURING, PaymentState.RELEASING),
                        payment,
                        Transition.end(PaymentState.COMPLETED, null));
            // the wallet refused the capture and kept the hold
            case CaptureFailed r -> onlyIn(PaymentState.CAPTURING, payment, release(payment, r.reason()));
            // only the wallet's word ends a release: REVERSED is never reached while money is held
            case FundsReleased r ->
                onlyIn(PaymentState.RELEASING, payment, Transition.end(PaymentState.REVERSED, null));
        };
    }

    /**
     * What to do with a payment whose deadline passed: re-send its command at most {@code maxResends} times (a step's
     * first send is attempt 1), then release the hold.
     *
     * <p>A release is re-sent forever. Giving it up would end the payment, or leave it stuck, with money still held.
     *
     * @throws IllegalStateException if the payment waits for nothing
     */
    public static Overdue overdue(Payment payment, int maxResends) {
        Command command = currentCommand(payment);
        if (payment.state() == PaymentState.RELEASING || payment.stepAttempts() <= maxResends) {
            return new Overdue.Resend(command);
        }
        return new Overdue.Compensate(release(payment, TIMEOUT));
    }

    /** The command whose reply the payment waits for. */
    private static Command currentCommand(Payment payment) {
        return switch (payment.state()) {
            case RESERVING -> reserveFunds(payment);
            case RISK_CHECKING -> checkRisk(payment);
            case CAPTURING -> captureFunds(payment);
            case RELEASING -> releaseFunds(payment);
            case RECEIVED, COMPLETED, REVERSED, REJECTED ->
                throw new IllegalStateException(
                        "payment " + payment.id() + " is " + payment.state() + " and waits for no reply");
        };
    }

    /** Compensation. Safe even if nothing is held: the wallet answers, and refuses a reserve that arrives later. */
    private static Transition release(Payment payment, String reason) {
        return new Transition(PaymentState.RELEASING, Optional.of(releaseFunds(payment)), reason);
    }

    private static Optional<Transition> onlyIn(PaymentState expected, Payment payment, Transition transition) {
        return onlyIn(Set.of(expected), payment, transition);
    }

    private static Optional<Transition> onlyIn(Set<PaymentState> expected, Payment payment, Transition transition) {
        return expected.contains(payment.state()) ? Optional.of(transition) : Optional.empty();
    }

    private static ReserveFunds reserveFunds(Payment p) {
        return new ReserveFunds(p.id(), p.payer(), p.amount(), p.currency());
    }

    private static CheckRisk checkRisk(Payment p) {
        return new CheckRisk(p.id(), p.payer(), p.amount(), p.currency());
    }

    private static CaptureFunds captureFunds(Payment p) {
        return new CaptureFunds(p.id(), p.payer(), p.payee(), p.amount(), p.currency());
    }

    private static ReleaseFunds releaseFunds(Payment p) {
        return new ReleaseFunds(p.id(), p.payer(), p.amount());
    }

    private SagaStateMachine() {}
}
