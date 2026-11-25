package com.siddheshfulawade.payflow.risk.app;

import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.risk.domain.RiskDecision;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.RiskRule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The effect of a CheckRisk: decides the payment once, and returns the reply.
 *
 * <p>Two layers of idempotency: the inbox drops a redelivered message (same message id), and the decision row answers
 * a re-sent command (the sweeper uses a new message id) without counting its amount or its velocity slot again.
 *
 * <p>Locking: first the payment (its decision row), then the payer's daily total. Every check takes its locks in this
 * order, so checks never wait for each other in a circle.
 *
 * <p>The velocity check is a network call inside the database transaction, on purpose. Counting before the commit
 * means an unknown count approves nothing: the call throws (fail closed). Counting for a transaction that then rolls
 * back is harmless: the window holds distinct payment ids, so the next check of the payment finds its own slot and pays
 * nothing. The call is bounded to 1 s (see HazelcastVelocityCounter), so the row locks are not held for long.
 *
 * <p>Accepted: a lost count can approve more than 5 payments in a window (split-brain, all members restarted, or an
 * owner lost before its backup caught up). Velocity is a risk control; the daily total and the decision stay exact.
 *
 * <p>Failures considered:
 *
 * <ul>
 *   <li>crash before the commit: inbox row, decision, daily total and reply all roll back; Kafka redelivers
 *   <li>crash after the velocity count, before the commit: the same; the redelivery re-uses its velocity slot. If it
 *       is never redelivered, the slot expires with the window: at worst one payment too few, never one too many
 *   <li>crash after the commit, before the offset commit: redelivered; the inbox re-sends the stored reply
 *   <li>crash before or after the reply is published: the reply is in the outbox; the relay sends it (again)
 *   <li>velocity cluster unreachable or slow: the call throws and everything rolls back (fail closed). The record is
 *       parked after one delivery and the payment's sweeper re-sends the check. One listener thread serves all
 *       partitions, so in an outage each instance gets through about one check per second
 *   <li>two copies of a check at once: the second waits on the decision row, then answers from the committed decision
 *   <li>two payments of one payer at once: the second waits on the daily total, then sees the first one's amount
 * </ul>
 */
@Service
public class RiskCommandHandler {

    private final RiskDecisions decisions;
    private final DailyTotals dailyTotals;
    private final VelocityCounter velocity;
    private final RiskLimits limits;
    private final Clock clock;

    public RiskCommandHandler(
            RiskDecisions decisions,
            DailyTotals dailyTotals,
            VelocityCounter velocity,
            RiskLimits limits,
            Clock clock) {
        this.decisions = decisions;
        this.dailyTotals = dailyTotals;
        this.velocity = velocity;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * MANDATORY: the effect must commit or roll back with the inbox row and the reply, so it runs only inside the
     * idempotent consumer's transaction.
     *
     * @throws PoisonMessageException if the message is not a risk command, or contradicts the payment's decision
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Envelope> handle(Envelope command) {
        Reply reply = switch (command.payload()) {
            case CheckRisk c -> check(c);
            // spelled out, no default: a new command type does not compile until it is decided here
            case ReserveFunds c -> throw notForRisk();
            case CaptureFunds c -> throw notForRisk();
            case ReleaseFunds c -> throw notForRisk();
            case Reply r -> throw notForRisk();
        };
        // the orchestrator accepts a reply only for its current attempt, so the reply carries the command's attempt
        return Optional.of(Envelope.of(reply, command.attempt(), clock));
    }

    private Reply check(CheckRisk command) {
        UUID payment = command.paymentId();
        // Claims the decision. Until we commit, the row is invisible and other claims of this payment wait on it.
        if (!decisions.claim(payment, command.payer(), command.amount())) {
            return existingAnswer(command);
        }
        Instant now = clock.instant();
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Optional<RiskRule> broken = brokenRule(command, now, date);
        if (broken.isPresent()) {
            decisions.reject(payment, broken.get());
            return new RiskRejected(payment, broken.get().name());
        }
        // only approved amounts count towards the day
        dailyTotals.add(command.payer(), date, command.amount(), limits.daily());
        decisions.approve(payment);
        return new RiskApproved(payment);
    }

    /** The first rule the payment breaks. Velocity is last, so a payment refused by another rule takes no slot. */
    private Optional<RiskRule> brokenRule(CheckRisk command, Instant now, LocalDate date) {
        if (limits.exceedsSingle(command.amount())) {
            return Optional.of(RiskRule.SINGLE_LIMIT);
        }
        // locked before the check: no other payment can use the same headroom between the check and the add
        long total = dailyTotals.lock(command.payer(), date);
        if (limits.exceedsDaily(total, command.amount())) {
            return Optional.of(RiskRule.DAILY_LIMIT);
        }
        return velocity.allow(command.payer(), command.paymentId(), now)
                ? Optional.empty()
                : Optional.of(RiskRule.VELOCITY);
    }

    /** The answer of the decision that stopped our claim. Decisions are never deleted, so it must be there. */
    private Reply existingAnswer(CheckRisk command) {
        UUID payment = command.paymentId();
        RiskDecision decision = decisions
                .find(payment)
                .orElseThrow(() -> new IllegalStateException("decision of payment " + payment + " disappeared"));
        // an approval of one amount must never answer for another; fixed text, no account values
        if (!decision.isFor(command.payer(), command.amount())) {
            throw new PoisonMessageException("command contradicts the payment's risk decision");
        }
        return switch (decision.verdict()) {
            case APPROVED -> new RiskApproved(payment);
            case REJECTED -> new RiskRejected(payment, decision.rule().name());
            case PENDING -> throw new IllegalStateException("payment " + payment + " was committed undecided");
        };
    }

    private static PoisonMessageException notForRisk() {
        return new PoisonMessageException("not a risk command");
    }
}
