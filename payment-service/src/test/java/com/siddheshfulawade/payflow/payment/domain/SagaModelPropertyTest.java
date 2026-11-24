package com.siddheshfulawade.payflow.payment.domain;

import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.NOW;
import static com.siddheshfulawade.payflow.payment.support.PaymentFixtures.payment;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Command;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.Message;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;

/**
 * The real {@link SagaStateMachine} against a small model of everything around it (REQ-PF-010 to 014): a wallet that
 * keeps one hold per payment (permanent reserve decision, tombstone after a release, a release of a captured hold is
 * answered FundsCaptured, a refused capture leaves the hold RESERVED), a risk service with a permanent decision, and
 * a network that loses, duplicates, reorders and delays commands and replies. The payment side may also crash
 * before its commit (nothing changes, the message stays), publish late (outbox lag) and time out at any moment.
 *
 * <p>Safety, checked after every single step, also while every remaining message is delivered late after the end:
 * one terminal state, never left; attempts only grow within a state; at most one capture; COMPLETED only when the
 * wallet captured; REVERSED and REJECTED only when nothing is held or captured; a reply is accepted only for the
 * current attempt; every command equals the one built from the row, and is sent only in its own state, and CaptureFunds
 * never after a ReleaseFunds. Liveness: after a fair phase (everything delivered, timeouts fire) the payment is
 * terminal within a bounded number of rounds.
 */
class SagaModelPropertyTest {

    private static final int MAX_RESENDS = 3;
    private static final int FAIR_ROUNDS = 40;

    private enum Hold {
        NONE,
        RESERVED,
        REJECTED,
        RELEASED,
        CAPTURED
    }

    /** kind 0-4 deliver, 5 lose, 6 duplicate, 7 time out, 8 relay the outbox, 9 crash before the commit */
    private record Op(int kind, int pick) {}

    private record Scenario(boolean reserveOk, boolean riskOk, boolean captureOk, List<Op> ops, long seed) {}

    /** The attempt is the one the command carried; replies copy it, as wallet and risk do. */
    private record Msg(Message payload, int attempt) {}

    @Provide
    Arbitrary<Scenario> scenarios() {
        // true four times in five, so that the happy path is reached often
        Arbitrary<Boolean> mostlyYes = Arbitraries.integers().between(0, 4).map(i -> i != 0);
        Arbitrary<Op> op = Combinators.combine(
                        Arbitraries.integers().between(0, 9),
                        Arbitraries.integers().between(0, 999))
                .as(Op::new);
        return Combinators.combine(mostlyYes, mostlyYes, mostlyYes, op.list().ofMaxSize(80), Arbitraries.longs())
                .as(Scenario::new);
    }

    // REQ-PF-010, 011, 012, 013: safety at every step, and liveness after a fair phase
    @Property(tries = 1500, shrinking = ShrinkingMode.BOUNDED)
    void safetyAtEveryStepAndTerminalAfterAFairPhase(@ForAll("scenarios") Scenario scenario) {
        new World(scenario).run();
    }

    private static final class World {
        private final Scenario scenario;
        private final Random random;
        private Payment payment;
        private Hold hold = Hold.NONE;
        private int captures;
        private final List<Msg> network = new ArrayList<>();
        private final List<Msg> outbox = new ArrayList<>();
        /** Lost on the way as far as the saga can tell; delivered late at the end to prove nothing breaks. */
        private final List<Msg> limbo = new ArrayList<>();

        private final List<String> history = new ArrayList<>();
        private PaymentState terminalSeen;
        private int terminalEntries;
        private boolean releaseSent;

        World(Scenario scenario) {
            this.scenario = scenario;
            this.random = new Random(scenario.seed());
        }

        void run() {
            Payment received = payment(PaymentState.RECEIVED);
            payment = received;
            applyTransition(SagaStateMachine.start(received));
            check("created");

            for (Op op : scenario.ops()) {
                step(op);
                check(op.toString());
            }

            fairPhase();
            require(payment.state().terminal(), "not terminal after " + FAIR_ROUNDS + " fair rounds");

            lateDeliveryOfEverythingLeft();
        }

        // ---- the world ----

        private void step(Op op) {
            int size = network.size();
            switch (op.kind()) {
                case 0, 1, 2, 3, 4 -> {
                    if (size > 0) {
                        deliver(network.remove(op.pick() % size), true);
                    }
                }
                case 5 -> {
                    if (size > 0) {
                        limbo.add(network.remove(op.pick() % size));
                    }
                }
                case 6 -> {
                    if (size > 0) {
                        network.add(network.get(op.pick() % size));
                    }
                }
                case 7 -> sweep(true);
                case 8 -> relay();
                case 9 -> crash(op.pick());
                default -> throw new IllegalStateException("kind " + op.kind());
            }
        }

        /** The step runs and the transaction rolls back: no state change, nothing published, the message stays. */
        private void crash(int pick) {
            Payment before = payment;
            int outboxBefore = outbox.size();
            int networkBefore = network.size();
            if (pick % 2 == 0 && !network.isEmpty()) {
                Msg m = network.get(pick % network.size());
                if (m.payload() instanceof Reply) {
                    deliver(m, false);
                }
            } else {
                sweep(false);
            }
            require(payment.equals(before), "a rolled-back step changed the payment");
            require(outbox.size() == outboxBefore && network.size() == networkBefore, "a rolled-back step sent");
        }

        private void relay() {
            network.addAll(outbox);
            outbox.clear();
        }

        private void fairPhase() {
            for (int round = 1; round <= FAIR_ROUNDS && !payment.state().terminal(); round++) {
                relay();
                for (int guard = 0; guard < 500 && !network.isEmpty(); guard++) {
                    deliver(network.remove(0), true);
                    check("fair delivery");
                    relay();
                }
                if (!payment.state().terminal()) {
                    sweep(true);
                    check("fair timeout");
                }
            }
        }

        /** Every message that is still anywhere arrives now, in random order, after the payment ended. */
        private void lateDeliveryOfEverythingLeft() {
            List<Msg> late = new ArrayList<>(limbo);
            late.addAll(network);
            late.addAll(outbox);
            limbo.clear();
            network.clear();
            outbox.clear();
            while (!late.isEmpty()) {
                deliver(late.remove(random.nextInt(late.size())), true);
                check("late delivery");
                late.addAll(network);
                network.clear();
            }
        }

        // ---- delivery ----

        private void deliver(Msg m, boolean commit) {
            if (m.payload() instanceof Command command) {
                network.add(new Msg(serve(command), m.attempt()));
            } else if (m.payload() instanceof Reply reply) {
                replyToPayment(reply, m.attempt(), commit);
            } else {
                throw new IllegalStateException("neither command nor reply");
            }
        }

        private void replyToPayment(Reply reply, int attempt, boolean commit) {
            Optional<Transition> transition = SagaStateMachine.on(payment, reply, attempt);
            if (transition.isEmpty()) {
                return;
            }
            require(!payment.state().terminal(), "a terminal payment accepted " + reply);
            require(
                    attempt == payment.stepAttempts(),
                    "accepted " + reply + " of attempt " + attempt + " on attempt " + payment.stepAttempts());
            if (commit) {
                applyTransition(transition.get());
            }
        }

        // ---- the payment side: what TransitionWriter and the sweeper store ----

        private void applyTransition(Transition t) {
            require(
                    t.command().isPresent() != t.to().terminal(),
                    "a move sends a command exactly when the payment goes on: " + t);
            Payment next = payment.movedTo(t.to(), t.to().terminal() ? null : NOW.plusSeconds(10), NOW);
            commit(next);
            t.command().ifPresent(c -> publish(c, next.stepAttempts()));
        }

        private void sweep(boolean commit) {
            if (payment.state() == PaymentState.RECEIVED || payment.state().terminal()) {
                return;
            }
            Overdue overdue = SagaStateMachine.overdue(payment, MAX_RESENDS);
            switch (overdue) {
                case Overdue.Resend resend -> {
                    require(
                            payment.state() == PaymentState.RELEASING || payment.stepAttempts() <= MAX_RESENDS,
                            "re-sent a step more than " + MAX_RESENDS + " times");
                    if (commit) {
                        Payment next = new Payment(
                                payment.id(),
                                payment.clientId(),
                                payment.idempotencyKey(),
                                payment.requestHash(),
                                payment.payer(),
                                payment.payee(),
                                payment.amount(),
                                payment.currency(),
                                payment.state(),
                                payment.stepAttempts() + 1,
                                NOW.plusSeconds(10),
                                payment.version() + 1,
                                payment.createdAt(),
                                NOW);
                        commit(next);
                        publish(resend.command(), next.stepAttempts());
                    }
                }
                case Overdue.Compensate compensate -> {
                    require(
                            payment.state() != PaymentState.RELEASING && payment.stepAttempts() > MAX_RESENDS,
                            "compensated too early or a release gave up");
                    if (commit) {
                        applyTransition(compensate.transition());
                    }
                }
            }
        }

        private void commit(Payment next) {
            require(!payment.state().terminal(), "left terminal state " + payment.state());
            if (next.state() == payment.state()) {
                require(next.stepAttempts() == payment.stepAttempts() + 1, "attempt did not grow by one");
            } else if (!next.state().terminal()) {
                require(next.stepAttempts() == 1, "a new step does not start on attempt 1");
            }
            require(next.version() == payment.version() + 1, "version");
            if (next.state().terminal()) {
                terminalEntries++;
                require(terminalEntries == 1, "second terminal state");
            }
            payment = next;
        }

        private void publish(Command c, int attempt) {
            require(attempt == payment.stepAttempts(), "command attempt " + attempt + " differs from the row's");
            require(c.equals(expectedCommand()), "payload differs from the one built from the row: " + c);
            if (c instanceof ReleaseFunds) {
                releaseSent = true;
            }
            require(!(c instanceof CaptureFunds && releaseSent), "CaptureFunds sent after a ReleaseFunds");
            outbox.add(new Msg(c, attempt));
        }

        /** The one command the current state may send, built from the row alone. */
        private Command expectedCommand() {
            Payment p = payment;
            return switch (p.state()) {
                case RESERVING -> new ReserveFunds(p.id(), p.payer(), p.amount(), p.currency());
                case RISK_CHECKING -> new CheckRisk(p.id(), p.payer(), p.amount(), p.currency());
                case CAPTURING -> new CaptureFunds(p.id(), p.payer(), p.payee(), p.amount(), p.currency());
                case RELEASING -> new ReleaseFunds(p.id(), p.payer(), p.amount());
                case RECEIVED, COMPLETED, REVERSED, REJECTED ->
                    throw new AssertionError("a command was sent in " + p.state() + history());
            };
        }

        // ---- the services around: idempotent, permanent decisions ----

        private Reply serve(Command command) {
            return switch (command) {
                case ReserveFunds c -> reserve();
                case CaptureFunds c -> capture();
                case ReleaseFunds c -> release();
                case CheckRisk c ->
                    scenario.riskOk() ? new RiskApproved(payment.id()) : new RiskRejected(payment.id(), "VELOCITY");
            };
        }

        private Reply reserve() {
            return switch (hold) {
                case NONE -> {
                    hold = scenario.reserveOk() ? Hold.RESERVED : Hold.REJECTED;
                    yield scenario.reserveOk()
                            ? new FundsReserved(payment.id())
                            : new FundsRejected(payment.id(), "INSUFFICIENT_FUNDS");
                }
                case RESERVED, CAPTURED -> new FundsReserved(payment.id());
                case REJECTED -> new FundsRejected(payment.id(), "INSUFFICIENT_FUNDS");
                // the tombstone: a reserve that arrives after the release must not hold money
                case RELEASED -> new FundsRejected(payment.id(), "NO_ACTIVE_HOLD");
            };
        }

        private Reply capture() {
            return switch (hold) {
                case NONE, RELEASED, REJECTED -> new CaptureFailed(payment.id(), "NO_ACTIVE_HOLD");
                case CAPTURED -> new FundsCaptured(payment.id());
                case RESERVED -> {
                    if (!scenario.captureOk()) {
                        // the hold stays RESERVED
                        yield new CaptureFailed(payment.id(), "PAYEE_FROZEN");
                    }
                    hold = Hold.CAPTURED;
                    captures++;
                    yield new FundsCaptured(payment.id());
                }
            };
        }

        private Reply release() {
            return switch (hold) {
                case NONE, RESERVED -> {
                    hold = Hold.RELEASED;
                    yield new FundsReleased(payment.id());
                }
                case RELEASED, REJECTED -> new FundsReleased(payment.id());
                // the truth: the payee has the money
                case CAPTURED -> new FundsCaptured(payment.id());
            };
        }

        // ---- the invariants ----

        private void check(String after) {
            history.add(after + " -> " + payment.state() + "#" + payment.stepAttempts() + " hold=" + hold);
            PaymentState state = payment.state();
            if (state.terminal()) {
                if (terminalSeen == null) {
                    terminalSeen = state;
                }
                require(terminalSeen == state, "terminal state changed from " + terminalSeen + " to " + state);
            }
            require(captures <= 1, "captured more than once");
            if (state == PaymentState.COMPLETED) {
                require(hold == Hold.CAPTURED && captures == 1, "COMPLETED but the wallet did not capture once");
                require(
                        scenario.reserveOk() && scenario.riskOk() && scenario.captureOk(),
                        "COMPLETED although a step was refused");
            }
            if (state == PaymentState.REVERSED || state == PaymentState.REJECTED) {
                require(
                        hold != Hold.RESERVED && hold != Hold.CAPTURED,
                        state + " while money is held or captured: " + hold);
            }
        }

        private void require(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message + history());
            }
        }

        private String history() {
            return "\nhistory: " + history;
        }
    }
}
