package com.siddheshfulawade.payflow.risk.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.risk.support.RiskIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three rules (REQ-PF-031) with the real database and the real Hazelcast counter. Limits are inclusive. */
class RiskRulesTest extends RiskIntegrationTest {

    private static final long SINGLE = 5_000_000;
    private static final long DAILY = 20_000_000;

    private final UUID payment = UUID.randomUUID();
    private final String payer = newPayer();

    private static void assertApproved(Envelope reply) {
        assertThat(reply.payload()).isEqualTo(new RiskApproved(reply.paymentId()));
    }

    private static void assertRejected(Envelope reply, String rule) {
        assertThat(reply.payload()).isEqualTo(new RiskRejected(reply.paymentId(), rule));
    }

    /** A new payment of this test's payer; it must be approved. */
    private void approvedPayment(long amount) {
        assertApproved(pay(payer, amount));
    }

    // ---- approval ----

    @Test
    @DisplayName("REQ-PF-031 a small payment is approved: decision stored, total counted, reply carries the attempt")
    void approves() {
        Envelope reply = apply(check(payment, payer, 1_000, 3));

        assertThat(reply.payload()).isEqualTo(new RiskApproved(payment));
        assertThat(reply.attempt()).as("reply attempt = command attempt").isEqualTo(3);
        assertThat(decision(payment))
                .containsEntry("payer", payer)
                .containsEntry("amount", 1_000L)
                .containsEntry("decision", "APPROVED")
                .containsEntry("rule", null);
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
        assertThat(velocity.callsFor(payer)).isEqualTo(1);
    }

    // ---- single payment limit ----

    @Test
    @DisplayName("REQ-PF-031 exactly 5,000,000 is approved (the single limit is inclusive)")
    void singleLimitInclusive() {
        assertApproved(apply(check(payment, payer, SINGLE)));
    }

    @Test
    @DisplayName("REQ-PF-031 5,000,001 is rejected as SINGLE_LIMIT: stored, nothing added to the daily total")
    void singleLimitExceeded() {
        Envelope reply = apply(check(payment, payer, SINGLE + 1, 2));

        assertThat(reply.payload()).isEqualTo(new RiskRejected(payment, "SINGLE_LIMIT"));
        assertThat(reply.attempt()).isEqualTo(2);
        assertThat(decision(payment)).containsEntry("decision", "REJECTED").containsEntry("rule", "SINGLE_LIMIT");
        assertThat(dailyTotal(payer)).as("rejected amounts are not counted").isZero();
    }

    // ---- daily limit ----

    @Test
    @DisplayName("REQ-PF-031 a daily total of exactly 20,000,000 is allowed, one more minor unit is DAILY_LIMIT")
    void dailyLimitInclusive() {
        for (int i = 0; i < 4; i++) {
            approvedPayment(SINGLE);
        }
        assertThat(dailyTotal(payer)).isEqualTo(DAILY);

        Envelope reply = apply(check(payment, payer, 1));

        assertThat(reply.payload()).isEqualTo(new RiskRejected(payment, "DAILY_LIMIT"));
        assertThat(decision(payment)).containsEntry("rule", "DAILY_LIMIT");
        assertThat(dailyTotal(payer)).as("a rejected payment adds nothing").isEqualTo(DAILY);
    }

    @Test
    @DisplayName(
            "REQ-PF-031 a payment that would cross the daily limit by 1 is rejected, one that fits exactly is approved")
    void dailyLimitCrossedByOne() {
        for (int i = 0; i < 3; i++) {
            approvedPayment(SINGLE);
        }
        approvedPayment(SINGLE - 10); // total 19,999,990

        Envelope tooMuch = apply(check(payment, payer, 11));
        Envelope exactly = apply(check(UUID.randomUUID(), payer, 10));

        assertRejected(tooMuch, "DAILY_LIMIT");
        assertApproved(exactly);
        assertThat(dailyTotal(payer)).isEqualTo(DAILY);
    }

    @Test
    @DisplayName("REQ-PF-031 the daily total is per payer")
    void dailyTotalIsPerPayer() {
        String other = newPayer();
        for (int i = 0; i < 4; i++) {
            approvedPayment(SINGLE);
        }

        assertApproved(pay(other, SINGLE));

        assertThat(dailyTotal(other)).isEqualTo(SINGLE);
        assertThat(dailyTotal(payer)).isEqualTo(DAILY);
    }

    // ---- velocity ----

    @Test
    @DisplayName(
            "REQ-PF-031 the 5th payment in a minute is approved, the 6th is rejected as VELOCITY and adds no amount")
    void velocityLimit() {
        for (int i = 0; i < 5; i++) {
            approvedPayment(100);
        }

        Envelope reply = apply(check(payment, payer, 100));

        assertThat(reply.payload()).isEqualTo(new RiskRejected(payment, "VELOCITY"));
        assertThat(decision(payment)).containsEntry("decision", "REJECTED").containsEntry("rule", "VELOCITY");
        assertThat(dailyTotal(payer)).as("only the 5 approved amounts").isEqualTo(500);
    }

    @Test
    @DisplayName("REQ-PF-031 velocity time comes from the injected clock: 59.999 s still counts, 60 s does not")
    void velocityUsesTheServiceClock() {
        clock.set(Instant.parse("2030-06-01T10:00:00Z"));
        for (int i = 0; i < 5; i++) {
            approvedPayment(100);
        }

        clock.set(Instant.parse("2030-06-01T10:00:59.999Z"));
        Envelope early = apply(check(UUID.randomUUID(), payer, 100));
        clock.set(Instant.parse("2030-06-01T10:01:00Z"));
        Envelope afterWindow = apply(check(UUID.randomUUID(), payer, 100));

        assertRejected(early, "VELOCITY");
        assertApproved(afterWindow);
    }

    // ---- order of the rules ----

    @Test
    @DisplayName("REQ-PF-031 over the single limit AND over the daily limit: the rejection names SINGLE_LIMIT")
    void singleBeatsDaily() {
        for (int i = 0; i < 3; i++) {
            approvedPayment(SINGLE);
        }
        approvedPayment(4_000_000); // total 19,000,000

        assertRejected(apply(check(payment, payer, 6_000_000)), "SINGLE_LIMIT");
    }

    @Test
    @DisplayName("REQ-PF-031 over the daily limit AND the velocity limit: the rejection names DAILY_LIMIT")
    void dailyBeatsVelocity() {
        for (int i = 0; i < 5; i++) {
            approvedPayment(4_000_000); // 5 slots used, daily total exactly 20,000,000
        }

        assertRejected(apply(check(payment, payer, 1)), "DAILY_LIMIT");
    }

    @Test
    @DisplayName("REQ-PF-031 over the single limit AND the velocity limit: the rejection names SINGLE_LIMIT")
    void singleBeatsVelocity() {
        for (int i = 0; i < 5; i++) {
            approvedPayment(100);
        }

        assertRejected(apply(check(payment, payer, SINGLE + 1)), "SINGLE_LIMIT");
    }

    @Test
    @DisplayName("REQ-PF-031 a payment rejected by the single or daily rule never asks the velocity counter")
    void earlierRejectionDoesNotConsultVelocity() {
        for (int i = 0; i < 4; i++) {
            approvedPayment(4_000_000); // 16,000,000
        }
        long before = velocity.callsFor(payer);

        assertRejected(pay(payer, SINGLE + 1), "SINGLE_LIMIT");
        assertRejected(pay(payer, SINGLE), "DAILY_LIMIT"); // 21,000,000

        assertThat(velocity.callsFor(payer)).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "REQ-PF-031 payments rejected by the single rule take no velocity slot: the 5th approved payment still fits")
    void singleRejectionUsesNoSlot() {
        for (int i = 0; i < 4; i++) {
            approvedPayment(100);
        }
        for (int i = 0; i < 3; i++) {
            assertRejected(pay(payer, SINGLE + 1), "SINGLE_LIMIT");
        }

        assertApproved(pay(payer, 100));
    }

    @Test
    @DisplayName(
            "REQ-PF-031 payments rejected by the daily rule take no velocity slot: the 5th approved payment still fits")
    void dailyRejectionUsesNoSlot() {
        for (int i = 0; i < 4; i++) {
            approvedPayment(4_000_000); // 16,000,000, 4 slots
        }
        for (int i = 0; i < 2; i++) {
            assertRejected(pay(payer, SINGLE), "DAILY_LIMIT");
        }

        assertApproved(pay(payer, 100));
    }

    @Test
    @DisplayName("REQ-PF-031 the velocity count belongs to the payer: another payer starts at zero")
    void velocityIsPerPayer() {
        for (int i = 0; i < 5; i++) {
            approvedPayment(100);
        }

        assertApproved(pay(newPayer(), 100));
    }
}
