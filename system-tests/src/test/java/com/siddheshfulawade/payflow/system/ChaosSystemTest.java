package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Chaos scenarios C1 to C5 of SPEC section 7 (C6, two payment-service instances, is cut, SPEC section 8) on one shared
 * compose stack. Each test sends a batch of 40 payments with mixed outcomes, injects its fault once half of the batch
 * is accepted, lets the components return and then requires (REQ-PF-050): every payment terminal within 3 minutes and
 * the money invariants of {@link Scenario#assertInvariants}.
 *
 * <p>Not covered: "reconciliation of the run reports only MATCHED" (SPEC section 7, last invariant). The reconciliation
 * job accepts business dates up to today minus 2 only, so it cannot see payments made now, and this test does not fake
 * it.
 *
 * <p>The batch is deterministic: 5 rich payers send 3 small payments (COMPLETED) and one over the risk limit
 * (REVERSED); 5 poor payers send 4 payments above their balance (REJECTED). Expected: 15 COMPLETED, 5 REVERSED,
 * 20 REJECTED. No payer reaches the velocity limit of 5 per minute, so only the faults can change an outcome.
 */
@Tag("system")
class ChaosSystemTest {

    private static final int PAYERS_PER_KIND = 5;
    private static final long RICH_BALANCE = 10_000_000;
    private static final long POOR_BALANCE = 5_000;
    private static final long PAYEE_BALANCE = 5_000;
    private static final long OVER_RISK_LIMIT = 5_000_001;
    private static final int BATCH = 40;

    @Test
    @DisplayName("REQ-PF-050 C1 wallet-service killed mid-batch and restarted: every payment terminal, money conserved")
    void c1WalletServiceKilledAndRestarted() throws Exception {
        Scenario scenario = new Scenario("C1");
        CompletableFuture<Void> sending = scenario.sendAsync(mixedBatch(scenario), 1);
        awaitHalfAccepted(scenario);

        try {
            SystemStack.composeRun("kill", "wallet-service");
            waitFor(Duration.ofSeconds(5)); // down for a while: commands pile up and replies are overdue
        } finally {
            SystemStack.composeQuietly("start", "wallet-service");
        }

        sending.get();
        assertMixedOutcome(scenario.assertInvariants(BATCH), 15, 5, 20);
    }

    @Test
    @DisplayName("REQ-PF-050 C2 Kafka broker restarted mid-batch: every payment terminal, money conserved")
    void c2KafkaRestarted() throws Exception {
        Scenario scenario = new Scenario("C2");
        CompletableFuture<Void> sending = scenario.sendAsync(mixedBatch(scenario), 1);
        awaitHalfAccepted(scenario);

        SystemStack.composeRun("restart", "kafka");

        sending.get();
        assertMixedOutcome(scenario.assertInvariants(BATCH), 15, 5, 20);
    }

    @Test
    @DisplayName("REQ-PF-050 C3 one risk member killed, the other serves: terminal, conserved, velocity still enforced")
    void c3RiskMemberKilledVelocityStillEnforced() throws Exception {
        Scenario scenario = new Scenario("C3");
        CompletableFuture<Void> sending = scenario.sendAsync(mixedBatch(scenario), 1);
        awaitHalfAccepted(scenario);

        try {
            // risk-1 is not started again before the check: risk-2 alone must carry the rest, and the velocity limit
            // of REQ-PF-031 (5 payments per payer per 60 s) must hold with a member gone (REQ-PF-032)
            SystemStack.composeRun("kill", "risk-1");
            String payer = scenario.seed("velocity-payer", RICH_BALANCE);
            String payee = scenario.seed("velocity-payee", PAYEE_BALANCE);
            List<Scenario.Spec> velocity = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                velocity.add(scenario.spec(payer, payee, 10));
            }
            scenario.sendAsync(velocity, 1).get();
            sending.get();

            // 7 small payments from one payer inside a minute: 5 pass, the 6th and 7th break the velocity rule
            Map<String, Long> outcome = scenario.assertInvariants(BATCH + 7);
            assertMixedOutcome(outcome, 15 + 5, 5 + 2, 20);
        } finally {
            SystemStack.composeQuietly("start", "risk-1");
        }
    }

    @Test
    @DisplayName("REQ-PF-050 C4 payment-service paused past the step deadlines: sweeper drives every payment terminal")
    void c4PaymentServicePausedPastDeadlines() throws Exception {
        Scenario scenario = new Scenario("C4");
        CompletableFuture<Void> sending = scenario.sendAsync(mixedBatch(scenario), 1);
        awaitHalfAccepted(scenario);

        try {
            SystemStack.composeRun("pause", "payment-service");
            // the longest step deadline is the 30 s cap (REQ-PF-012): 45 s is past every deadline of every attempt
            waitFor(Duration.ofSeconds(45));
        } finally {
            SystemStack.composeQuietly("unpause", "payment-service");
        }

        sending.get();
        assertMixedOutcome(scenario.assertInvariants(BATCH), 15, 5, 20);
    }

    @Test
    @DisplayName(
            "REQ-PF-002 C5 every request sent three times at once with one key: one payment each, one capture each")
    void c5ClientRetriesEveryRequest() throws Exception {
        Scenario scenario = new Scenario("C5");

        CompletableFuture<Void> sending = scenario.sendAsync(mixedBatch(scenario), 3);
        awaitHalfAccepted(scenario);
        // a fault on top of the retries: some copies then hit a restarting wallet-service
        try {
            SystemStack.composeRun("kill", "wallet-service");
            waitFor(Duration.ofSeconds(5));
        } finally {
            SystemStack.composeQuietly("start", "wallet-service");
        }

        sending.get();
        assertThat(scenario.accepted()).as("every copy was answered").isEqualTo(BATCH * 3);
        // payments created = distinct keys is part of assertInvariants: 120 requests, 40 payments
        assertMixedOutcome(scenario.assertInvariants(BATCH), 15, 5, 20);
    }

    private static List<Scenario.Spec> mixedBatch(Scenario scenario) throws Exception {
        List<Scenario.Spec> specs = new ArrayList<>();
        for (int i = 0; i < PAYERS_PER_KIND; i++) {
            String rich = scenario.seed("rich" + i, RICH_BALANCE);
            String richPayee = scenario.seed("richpayee" + i, PAYEE_BALANCE);
            specs.add(scenario.spec(rich, richPayee, 1_000));
            specs.add(scenario.spec(rich, richPayee, 1_001));
            specs.add(scenario.spec(rich, richPayee, 1_002));
            specs.add(scenario.spec(rich, richPayee, OVER_RISK_LIMIT));

            String poor = scenario.seed("poor" + i, POOR_BALANCE);
            String poorPayee = scenario.seed("poorpayee" + i, PAYEE_BALANCE);
            for (int j = 0; j < 4; j++) {
                specs.add(scenario.spec(poor, poorPayee, POOR_BALANCE + 1_000));
            }
        }
        assertThat(specs).hasSize(BATCH);
        return specs;
    }

    private static void awaitHalfAccepted(Scenario scenario) {
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> scenario.accepted() >= BATCH / 2);
    }

    // no Thread.sleep: a pure wait is an Awaitility delay that nothing can cut short
    private static void waitFor(Duration pause) {
        await().pollDelay(pause).atMost(pause.plusSeconds(30)).until(() -> true);
    }

    private static void assertMixedOutcome(Map<String, Long> outcome, long completed, long reversed, long rejected) {
        assertThat(outcome)
                .as("outcome per state")
                .containsEntry("COMPLETED", completed)
                .containsEntry("REVERSED", reversed)
                .containsEntry("REJECTED", rejected);
    }
}
