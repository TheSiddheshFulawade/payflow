package com.siddheshfulawade.payflow.wallet.domain;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import java.util.Optional;
import java.util.UUID;

/**
 * The recorded decision for a payment, and the answers it dictates.
 *
 * <p>Invariant: the reserve decision is made once per payment and is permanent. Whatever is re-sent or arrives late
 * is answered from this row, never from today's balances, so a payment can never hold money a second time.
 *
 * <p>Invariant: a command that contradicts money that is held or captured (another payer or amount, or another
 * payee after the capture) is a poison message, never a rejection. A rejection would let the saga end REJECTED while
 * the money is still held. The message text is fixed and carries no values (they are account data).
 *
 * @param reason why the reserve was refused; null unless REJECTED
 * @param payee who received the money; null unless CAPTURED
 */
public record Hold(UUID paymentId, String payer, long amount, HoldState state, RejectReason reason, String payee) {

    /** The answer to a ReserveFunds once this hold exists. It never moves money. */
    public Reply answerToReserve(ReserveFunds command) {
        return switch (state) {
            case RELEASED -> rejected(RejectReason.NO_ACTIVE_HOLD);
            case REJECTED -> rejected(reason);
            case RESERVED, CAPTURED -> {
                requireSameMoney(command.payer(), command.amount());
                yield new FundsReserved(paymentId);
            }
        };
    }

    /** The answer to a ReleaseFunds that has nothing to give back, or empty: the held money must move back. */
    public Optional<Reply> answerToRelease() {
        return switch (state) {
            case RESERVED -> Optional.empty();
            case RELEASED, REJECTED -> Optional.of(new FundsReleased(paymentId));
            // the truth: the payee has the money. FundsReleased here would end the payment REFUNDED without a refund.
            case CAPTURED -> Optional.of(new FundsCaptured(paymentId));
        };
    }

    /** The answer to a CaptureFunds that this hold alone decides, or empty: the payee's wallet decides. */
    public Optional<Reply> answerToCapture(CaptureFunds command) {
        return switch (state) {
            case RELEASED, REJECTED -> Optional.of(captureFailed(RejectReason.NO_ACTIVE_HOLD));
            case RESERVED, CAPTURED -> {
                requireSameMoney(command.payer(), command.amount());
                if (state == HoldState.CAPTURED && !command.payee().equals(payee)) {
                    throw contradiction();
                }
                yield state == HoldState.CAPTURED ? Optional.of(new FundsCaptured(paymentId)) : Optional.empty();
            }
        };
    }

    private void requireSameMoney(String commandPayer, long commandAmount) {
        if (!payer.equals(commandPayer) || amount != commandAmount) {
            throw contradiction();
        }
    }

    private static PoisonMessageException contradiction() {
        return new PoisonMessageException("command contradicts the payment's hold");
    }

    private Reply rejected(RejectReason why) {
        return new FundsRejected(paymentId, why.name());
    }

    private Reply captureFailed(RejectReason why) {
        return new CaptureFailed(paymentId, why.name());
    }
}
