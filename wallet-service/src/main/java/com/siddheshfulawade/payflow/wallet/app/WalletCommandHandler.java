package com.siddheshfulawade.payflow.wallet.app;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.wallet.domain.Hold;
import com.siddheshfulawade.payflow.wallet.domain.HoldState;
import com.siddheshfulawade.payflow.wallet.domain.RejectReason;
import com.siddheshfulawade.payflow.wallet.domain.Wallet;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The effect of a wallet command: changes hold, balances and ledger, and returns the reply.
 *
 * <p>Two layers of idempotency: the inbox drops a redelivered message (same message id), and the hold row makes a
 * re-sent command (the sweeper uses a new message id) answer again without moving money again.
 *
 * <p>Locking: first the payment (its hold row), then the wallets in id order. Every command takes its locks in this
 * order, so commands never wait for each other in a circle. Money is only decided on rows this transaction locked.
 *
 * <p>Failures considered:
 *
 * <ul>
 *   <li>crash before the commit: inbox row, hold, balances, ledger and reply all roll back; Kafka redelivers
 *   <li>crash after the commit, before the offset commit: redelivered; the inbox re-sends the stored reply
 *   <li>crash before or after the reply is published: the reply is in the outbox; the relay sends it (again)
 *   <li>two copies of a command at once: the second waits on the hold row, then answers from the committed decision
 *   <li>a late ReserveFunds after ReleaseFunds: the RELEASED tombstone rejects it, so nothing stays held
 * </ul>
 */
@Service
public class WalletCommandHandler {

    private final Holds holds;
    private final Wallets wallets;
    private final Clock clock;

    public WalletCommandHandler(Holds holds, Wallets wallets, Clock clock) {
        this.holds = holds;
        this.wallets = wallets;
        this.clock = clock;
    }

    /**
     * MANDATORY: the effect must commit or roll back with the inbox row and the reply, so it runs only inside the
     * idempotent consumer's transaction.
     *
     * @throws PoisonMessageException if the message is not a wallet command
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Envelope> handle(Envelope command) {
        Reply reply = switch (command.payload()) {
            case ReserveFunds c -> reserve(c);
            case CaptureFunds c -> capture(c);
            case ReleaseFunds c -> release(c);
            // spelled out, no default: a new command type does not compile until it is decided here
            case CheckRisk c -> throw notForWallet();
            case Reply r -> throw notForWallet();
        };
        // the orchestrator accepts a reply only for its current attempt, so the reply carries the command's attempt
        return Optional.of(Envelope.of(reply, command.attempt(), clock));
    }

    private Reply reserve(ReserveFunds command) {
        UUID payment = command.paymentId();
        // Claims the decision. Until we commit, the row is invisible and other inserts for this payment wait on it.
        if (!holds.insertIfAbsent(payment, command.payer(), command.amount(), HoldState.RESERVED)) {
            return existing(payment).answerToReserve(command);
        }
        Optional<RejectReason> refusal = refusalToReserve(command);
        if (refusal.isPresent()) {
            holds.reject(payment, refusal.get());
            return new FundsRejected(payment, refusal.get().name());
        }
        wallets.reserve(payment, command.payer(), command.amount());
        return new FundsReserved(payment);
    }

    private Optional<RejectReason> refusalToReserve(ReserveFunds command) {
        // locked before the check: no other payment can spend the same money between the check and the update
        Wallet payer = wallets.lock(List.of(command.payer())).get(command.payer());
        return payer == null ? Optional.of(RejectReason.WALLET_UNKNOWN) : payer.refusesReserve(command.amount());
    }

    private Reply release(ReleaseFunds command) {
        UUID payment = command.paymentId();
        // No reserve yet: leave a tombstone, so a ReserveFunds that arrives later is rejected and the payment cannot
        // end REFUNDED with money still held.
        if (holds.insertIfAbsent(payment, command.payer(), command.amount(), HoldState.RELEASED)) {
            return new FundsReleased(payment);
        }
        Hold hold = existing(payment);
        Optional<Reply> answer = hold.answerToRelease();
        if (answer.isPresent()) {
            return answer.get();
        }
        // The hold's own payer and amount, never the command's: exactly what was reserved goes back. The wallet's
        // status does not matter: a frozen wallet gets its held money back too.
        wallets.release(payment, hold.payer(), hold.amount());
        holds.release(payment);
        return new FundsReleased(payment);
    }

    private Reply capture(CaptureFunds command) {
        UUID payment = command.paymentId();
        Optional<Hold> hold = holds.lock(payment);
        if (hold.isEmpty()) {
            // not recorded: nothing moved, and a capture that failed may be retried
            return new CaptureFailed(payment, RejectReason.NO_ACTIVE_HOLD.name());
        }
        Optional<Reply> answer = hold.get().answerToCapture(command);
        if (answer.isPresent()) {
            return answer.get();
        }
        Optional<RejectReason> refusal = refusalToCapture(command);
        if (refusal.isPresent()) {
            // the hold stays RESERVED: the orchestrator releases it (or retries the capture)
            return new CaptureFailed(payment, refusal.get().name());
        }
        wallets.capture(payment, command.payer(), command.payee(), command.amount());
        holds.capture(payment, command.payee());
        return new FundsCaptured(payment);
    }

    private Optional<RejectReason> refusalToCapture(CaptureFunds command) {
        // both wallets, in id order: "A pays B" and "B pays A" at the same time cannot deadlock
        Wallet payee = wallets.lock(List.of(command.payer(), command.payee())).get(command.payee());
        return payee == null ? Optional.of(RejectReason.PAYEE_UNKNOWN) : payee.refusesCredit();
    }

    /** The hold that stopped our insert. Holds are never deleted, so it must be there. */
    private Hold existing(UUID payment) {
        return holds.lock(payment)
                .orElseThrow(() -> new IllegalStateException("hold of payment " + payment + " disappeared"));
    }

    private static PoisonMessageException notForWallet() {
        return new PoisonMessageException("not a wallet command");
    }
}
