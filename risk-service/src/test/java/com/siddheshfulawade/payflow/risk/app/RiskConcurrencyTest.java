package com.siddheshfulawade.payflow.risk.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.risk.support.RiskIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Many threads at one payer through the idempotent consumer, like several service instances would be. */
class RiskConcurrencyTest extends RiskIntegrationTest {

    private final String payer = newPayer();

    private static long count(List<Envelope> replies, Class<?> type) {
        return replies.stream().filter(r -> type.isInstance(r.payload())).count();
    }

    private static long rejectedBy(List<Envelope> replies, String rule) {
        return replies.stream()
                .map(Envelope::payload)
                .filter(p ->
                        p instanceof RiskRejected rejected && rejected.rule().equals(rule))
                .count();
    }

    @Test
    @DisplayName(
            "REQ-PF-031 50 threads of 1,000,000 for one payer: exactly 20 approved, daily total exactly 20,000,000")
    void concurrentPaymentsNeverExceedTheDailyLimit() throws Exception {
        velocity.allowAll(); // the daily total is under test here, not velocity
        List<Callable<Envelope>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(run(check(UUID.randomUUID(), payer, 1_000_000)));
        }

        List<Envelope> replies = concurrently(tasks, TIMEOUT);

        assertThat(count(replies, RiskApproved.class)).as("approved").isEqualTo(20);
        assertThat(rejectedBy(replies, "DAILY_LIMIT")).as("DAILY_LIMIT").isEqualTo(30);
        assertThat(dailyTotal(payer)).isEqualTo(20_000_000);
        assertThat(jdbc.queryForObject("select count(*) from risk_decision", Integer.class))
                .isEqualTo(50);
        assertThat(dailyTotalRows(payer)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-031 20 concurrent payments with the real velocity counter: exactly 5 approved, 15 VELOCITY")
    void concurrentPaymentsNeverExceedTheVelocityLimit() throws Exception {
        List<Callable<Envelope>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(run(check(UUID.randomUUID(), payer, 100)));
        }

        List<Envelope> replies = concurrently(tasks, TIMEOUT);

        assertThat(count(replies, RiskApproved.class)).as("approved").isEqualTo(5);
        assertThat(rejectedBy(replies, "VELOCITY")).as("VELOCITY").isEqualTo(15);
        assertThat(dailyTotal(payer)).as("approved amounts only").isEqualTo(500);
    }

    @Test
    @DisplayName(
            "REQ-PF-021 the same payment checked by 4 threads with different message ids is decided and counted once")
    void samePaymentConcurrentlyIsDecidedOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            UUID payment = UUID.randomUUID();
            Envelope first = check(payment, payer, 1_000);
            List<Callable<Envelope>> tasks =
                    List.of(run(first), run(resent(first)), run(resent(first)), run(resent(first)));

            List<Envelope> replies = concurrently(tasks, TIMEOUT);

            assertThat(replies)
                    .as("round %d", round)
                    .allSatisfy(r -> assertThat(r.payload()).isEqualTo(new RiskApproved(payment)));
            assertThat(decisionRows(payment)).as("round %d", round).isEqualTo(1);
            assertThat(dailyTotal(payer)).as("round %d", round).isEqualTo((round + 1) * 1_000L);
        }
    }

    @Test
    @DisplayName("REQ-PF-021 concurrent copies of one payment take one velocity slot, so 5 payments still fit")
    void samePaymentConcurrentlyTakesOneSlot() throws Exception {
        UUID payment = UUID.randomUUID();
        Envelope first = check(payment, payer, 100);
        concurrently(List.of(run(first), run(resent(first)), run(resent(first))), TIMEOUT);

        for (int i = 0; i < 4; i++) {
            assertThat(pay(payer, 100).payload())
                    .as("payment %d after the copies", i + 2)
                    .isInstanceOf(RiskApproved.class);
        }

        assertThat(pay(payer, 100).payload()).isInstanceOf(RiskRejected.class);
    }

    @Test
    @DisplayName("REQ-PF-031 two payers never block each other's totals: both reach exactly their own 20,000,000")
    void payersAreIndependentUnderLoad() throws Exception {
        velocity.allowAll();
        String other = newPayer();
        List<Callable<Envelope>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(run(check(UUID.randomUUID(), payer, 1_000_000)));
            tasks.add(run(check(UUID.randomUUID(), other, 1_000_000)));
        }

        List<Envelope> replies = concurrently(tasks, TIMEOUT);

        assertThat(count(replies, RiskApproved.class)).isEqualTo(40);
        assertThat(dailyTotal(payer)).isEqualTo(20_000_000);
        assertThat(dailyTotal(other)).isEqualTo(20_000_000);
    }
}
