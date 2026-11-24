package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.risk.support.RiskIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cluster does not answer: the check is bounded, delivered once, parked, and the sweeper's re-send is decided
 * normally later. Two payments on one partition show that the first does not hold up the second.
 */
class VelocityDownIntegrationTest extends RiskIntegrationTest {

    private static int partitionOf(UUID payment) {
        // the producer's default partitioner for a keyed record
        return Utils.toPositive(Utils.murmur2(payment.toString().getBytes(StandardCharsets.UTF_8))) % Topics.PARTITIONS;
    }

    private static UUID anotherOnTheSamePartitionAs(UUID payment) {
        for (int i = 0; i < 10_000; i++) {
            UUID candidate = UUID.randomUUID();
            if (partitionOf(candidate) == partitionOf(payment)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no second payment on the same partition found");
    }

    private void send(Envelope command) {
        kafka.produceRaw(Topics.RISK_COMMANDS, command.paymentId().toString(), codec.encode(command));
    }

    private boolean parked(Envelope command) {
        return kafka.readRaw(Topics.deadLetter(Topics.RISK_COMMANDS)).stream()
                .map(ConsumerRecord::value)
                .anyMatch(v -> v.contains(command.messageId().toString()));
    }

    @Test
    @DisplayName(
            "REQ-PF-032 with the velocity cluster down, two payments on one partition are each delivered once, parked,"
                    + " and the second is not held up by the first")
    void unavailableVelocityDoesNotBlockThePartition() {
        UUID first = UUID.randomUUID();
        UUID second = anotherOnTheSamePartitionAs(first);
        String firstPayer = newPayer();
        String secondPayer = newPayer();
        Envelope firstCheck = check(first, firstPayer, 1_000);
        Envelope secondCheck = check(second, secondPayer, 2_000);
        // each check takes about as long as the real counter's bound before it gives up
        velocity.unavailableAfter(Duration.ofSeconds(1));

        send(firstCheck);
        send(secondCheck);

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(parked(firstCheck)).as("first parked").isTrue();
            assertThat(parked(secondCheck)).as("second parked").isTrue();
        });
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(velocity.calls())
                        .as("one delivery each: no retries behind the partition")
                        .hasSize(2));
        List<Instant> times = velocity.callTimes();
        assertThat(Duration.between(times.get(0), times.get(1)))
                .as("the second starts within one step deadline of the first")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(decisionRows(first) + decisionRows(second)).as("no decision").isZero();
        assertThat(dailyTotalRows(firstPayer) + dailyTotalRows(secondPayer))
                .as("no daily total")
                .isZero();
        assertThat(inboxRows())
                .as("no inbox row: a re-send is not taken for a duplicate")
                .isZero();
        assertThat(outboxRows()).as("no reply").isZero();
    }

    @Test
    @DisplayName("REQ-PF-021 after the cluster is back, the re-sent command (new message id) is decided normally, once")
    void resentCommandIsDecidedOnceAfterRecovery() {
        UUID payment = UUID.randomUUID();
        String payer = newPayer();
        Envelope original = check(payment, payer, 1_000);
        velocity.unavailableAfter(Duration.ofMillis(100));
        send(original);
        await().atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(parked(original)).isTrue());
        velocity.recover();
        Envelope resent = resent(original);

        send(resent);

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            assertThat(decisionRows(payment)).isEqualTo(1);
            assertThat(outboxRows()).isEqualTo(1);
        });
        assertThat(storedReply(resent).payload()).isEqualTo(new RiskApproved(payment));
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
        assertThat(velocity.callsFor(payer)).as("one failed check, one decided").isEqualTo(2);

        send(resent(original)); // a second re-send of the same step

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(decisionRows(payment)).isEqualTo(1);
            assertThat(dailyTotal(payer)).as("counted once").isEqualTo(1_000);
        });
        assertThat(velocity.callsFor(payer))
                .as("the decision answers; velocity is not asked again")
                .isEqualTo(2);
    }
}
