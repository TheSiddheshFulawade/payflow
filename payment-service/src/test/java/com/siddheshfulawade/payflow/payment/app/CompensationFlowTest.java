package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Compensation by replies: a refused step releases the hold, and only the wallet's answer ends the payment. */
class CompensationFlowTest extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    @BeforeEach
    void virtualTime() {
        clock.set(T0);
    }

    private String releaseTopic(Payment p) {
        return jdbc.queryForObject(
                "select topic from outbox where message_key = ? and payload like '%ReleaseFunds%'",
                String.class, p.id().toString());
    }

    @Test
    @DisplayName("REQ-PF-011 RiskRejected releases the hold: RELEASING, ReleaseFunds from the row, reason = the rule")
    void riskRejectionStartsTheRelease() {
        Payment p = newPayment(new NewPayment("carol", "dave", 1_234, "INR"));
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        Envelope rejection = reply(new RiskRejected(p.id(), "VELOCITY"));

        deliver(rejection);

        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(stepAttempts(p.id())).as("a new step starts at attempt 1").isEqualTo(1);
        Envelope release = onlyOutbox(p.id(), ReleaseFunds.class);
        assertThat(release.payload()).isEqualTo(new ReleaseFunds(p.id(), "carol", 1_234));
        assertThat(release.attempt()).isEqualTo(1);
        assertThat(releaseTopic(p)).isEqualTo(Topics.WALLET_COMMANDS);
        assertThat(deadline(p.id())).isEqualTo(T0.plus(Duration.ofSeconds(10)));
        assertThat(eventReason(p.id(), "RELEASING")).isEqualTo("VELOCITY");
        assertThat(eventMessageId(p.id(), "RELEASING")).isEqualTo(rejection.messageId());
    }

    @Test
    @DisplayName("REQ-PF-011 CaptureFailed releases the hold: RELEASING, ReleaseFunds, reason = the wallet's reason")
    void captureFailureStartsTheRelease() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.CAPTURING);

        deliver(reply(new CaptureFailed(p.id(), "PAYEE_FROZEN")));

        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(onlyOutbox(p.id(), ReleaseFunds.class).payload()).isEqualTo(new ReleaseFunds(p.id(), "alice", 500));
        assertThat(eventReason(p.id(), "RELEASING")).isEqualTo("PAYEE_FROZEN");
    }

    @Test
    @DisplayName("REQ-PF-011 FundsReleased ends REVERSED: no deadline, no further command, every transition audited")
    void releasedEndsReversed() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RELEASING);
        long commands = outboxCount(p.id());
        Envelope released = reply(new FundsReleased(p.id()));

        deliver(released);

        assertThat(state(p.id())).isEqualTo("REVERSED");
        assertThat(deadline(p.id())).isNull();
        assertThat(outboxCount(p.id())).isEqualTo(commands);
        assertThat(eventMessageId(p.id(), "REVERSED")).isEqualTo(released.messageId());
        assertThat(events(p.id()))
                .containsExactlyInAnyOrder(
                        "null->RECEIVED",
                        "RECEIVED->RESERVING",
                        "RESERVING->RISK_CHECKING",
                        "RISK_CHECKING->RELEASING",
                        "RELEASING->REVERSED");
    }

    @Test
    @DisplayName("REQ-PF-011 the wallet answers FundsCaptured to a release of a captured hold: the payment COMPLETES")
    void capturedWhileReleasingCompletes() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.CAPTURING);
        // the capture reply was lost, the capture timed out into a release ...
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(31));
            sweeper.sweep();
        }
        assertThat(state(p.id())).isEqualTo("RELEASING");
        long commands = outboxCount(p.id());

        // ... and the wallet reports the money moved
        deliver(reply(new FundsCaptured(p.id())));

        assertThat(state(p.id())).isEqualTo("COMPLETED");
        assertThat(deadline(p.id())).isNull();
        assertThat(outboxCount(p.id())).isEqualTo(commands);
        assertThat(events(p.id())).contains("RELEASING->COMPLETED").doesNotContain("RELEASING->REVERSED");
    }

    @Test
    @DisplayName("REQ-PF-021 a redelivered or re-sent RiskRejected adds no second ReleaseFunds")
    void duplicateRejectionReleasesOnce() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        Envelope rejection = reply(new RiskRejected(p.id(), "VELOCITY"));

        deliver(rejection);
        deliver(rejection);
        deliver(resent(rejection));

        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(1);
        assertThat(state(p.id())).isEqualTo("RELEASING");
    }

    @Test
    @DisplayName("REQ-PF-013 a RiskRejected of another attempt, or after the payment moved on, changes nothing")
    void lateRejectionIsIgnored() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        deliver(reply(new RiskRejected(p.id(), "VELOCITY"), 2));
        assertThat(state(p.id())).as("wrong attempt").isEqualTo("RISK_CHECKING");

        deliver(reply(new RiskApproved(p.id())));
        long version = version(p.id());
        deliver(reply(new RiskRejected(p.id(), "VELOCITY")));

        assertThat(state(p.id())).as("the check was already approved").isEqualTo("CAPTURING");
        assertThat(version(p.id())).isEqualTo(version);
        assertThat(commands(p.id(), ReleaseFunds.class)).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-013 while RELEASING, replies of the earlier steps are ignored; the release waits for its own")
    void earlierRepliesDoNotMoveAReleasingPayment() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RELEASING);
        long version = version(p.id());

        deliver(reply(new FundsReserved(p.id())));
        deliver(reply(new RiskApproved(p.id())));
        deliver(reply(new CaptureFailed(p.id(), "PAYEE_FROZEN")));
        deliver(reply(new RiskRejected(p.id(), "VELOCITY")));

        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(version(p.id())).isEqualTo(version);
        assertThat(commands(p.id(), CaptureFunds.class)).isEmpty();
        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(1);
    }

    @Test
    @DisplayName("REQ-PF-013 a reply after REVERSED changes nothing")
    void repliesAfterReversalAreIgnored() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.REVERSED);
        long version = version(p.id());

        deliver(reply(new FundsCaptured(p.id())));
        deliver(reply(new FundsReleased(p.id())));
        deliver(reply(new RiskRejected(p.id(), "VELOCITY")));

        assertThat(state(p.id())).isEqualTo("REVERSED");
        assertThat(version(p.id())).isEqualTo(version);
    }

    @Test
    @DisplayName("REQ-PF-020 the release command and the state change commit together: a failed append rolls both back")
    void releaseAppendFailureRollsBackTheTransition() {
        Payment p = newPayment();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        Envelope rejection = reply(new RiskRejected(p.id(), "VELOCITY"));
        makeOutboxInsertFail(Topics.WALLET_COMMANDS);
        Snapshot before = snapshotAll();

        assertThatThrownBy(() -> deliver(rejection)).isInstanceOf(RuntimeException.class);

        assertThat(snapshotAll()).as("state, event, inbox: all rolled back").isEqualTo(before);
        dropFailureTriggers();
        deliver(rejection);
        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(1);
    }
}
