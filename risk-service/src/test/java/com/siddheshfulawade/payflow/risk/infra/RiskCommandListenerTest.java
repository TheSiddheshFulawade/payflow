package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.OutboxRelay;
import com.siddheshfulawade.payflow.risk.support.RiskIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Commands travel through the real broker: risk.commands in, risk.replies out (relay run by the test). */
class RiskCommandListenerTest extends RiskIntegrationTest {

    @Autowired
    OutboxRelay relay;

    private final UUID payment = UUID.randomUUID();
    private final String payer = newPayer();

    private void send(Envelope command) {
        kafka.produceRaw(Topics.RISK_COMMANDS, command.paymentId().toString(), codec.encode(command));
    }

    /** Relays the outbox until the payment has at least {@code count} replies on risk.replies. */
    private List<ConsumerRecord<String, String>> awaitReplies(UUID id, int count) {
        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(repliesFor(id)).hasSizeGreaterThanOrEqualTo(count);
        });
        return repliesFor(id);
    }

    private List<ConsumerRecord<String, String>> repliesFor(UUID id) {
        return kafka.readRaw(Topics.RISK_REPLIES).stream()
                .filter(r -> id.toString().equals(r.key()))
                .toList();
    }

    @Test
    @DisplayName(
            "REQ-PF-031 CheckRisk on risk.commands is answered RiskApproved on risk.replies, keyed by payment, same attempt")
    void approvalEndToEnd() {
        send(check(payment, payer, 1_000, 2));

        List<ConsumerRecord<String, String>> replies = awaitReplies(payment, 1);

        Envelope reply = codec.decode(replies.get(0).value());
        assertThat(replies.get(0).key()).isEqualTo(payment.toString());
        assertThat(reply.payload()).isEqualTo(new RiskApproved(payment));
        assertThat(reply.attempt()).isEqualTo(2);
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("REQ-PF-031 an over-limit CheckRisk is answered RiskRejected naming its rule")
    void rejectionEndToEnd() {
        send(check(payment, payer, 5_000_001, 1));

        List<ConsumerRecord<String, String>> replies = awaitReplies(payment, 1);

        Envelope reply = codec.decode(replies.get(0).value());
        assertThat(replies.get(0).key()).isEqualTo(payment.toString());
        assertThat(reply.payload()).isEqualTo(new RiskRejected(payment, "SINGLE_LIMIT"));
        assertThat(dailyTotal(payer)).isZero();
    }

    @Test
    @DisplayName(
            "REQ-PF-021 the same CheckRisk delivered twice has one effect and its reply is re-sent with the same message id")
    void duplicateDeliveryHasOneEffect() {
        Envelope command = check(payment, payer, 1_000);

        send(command);
        send(command);

        List<Envelope> replies = awaitReplies(payment, 2).stream()
                .map(r -> codec.decode(r.value()))
                .toList();
        assertThat(replies).hasSize(2);
        assertThat(replies.get(0).messageId()).isEqualTo(replies.get(1).messageId());
        assertThat(replies).allSatisfy(r -> assertThat(r.payload()).isEqualTo(new RiskApproved(payment)));
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
        assertThat(decisionRows(payment)).isEqualTo(1);
        assertThat(velocity.callsFor(payer)).as("velocity asked once").isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 a sweeper re-send (new message id) over Kafka is answered again but counted once")
    void resentCommandOverKafkaCountsOnce() {
        Envelope command = check(payment, payer, 1_000);

        send(command);
        send(resent(command));

        List<Envelope> replies = awaitReplies(payment, 2).stream()
                .map(r -> codec.decode(r.value()))
                .toList();
        assertThat(replies).allSatisfy(r -> assertThat(r.payload()).isEqualTo(new RiskApproved(payment)));
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
        assertThat(velocity.callsFor(payer)).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "REQ-PF-020 a committed decision waits in the outbox until the relay runs: a crash before the publish loses nothing")
    void replyWaitsInTheOutboxForTheRelay() {
        Envelope command = check(payment, payer, 1_000);

        send(command);

        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            assertThat(decisionRows(payment)).isEqualTo(1);
            assertThat(outboxRows()).isEqualTo(1);
        });
        assertThat(repliesFor(payment))
                .as("not published yet: the relay has not run")
                .isEmpty();

        assertThat(awaitReplies(payment, 1)).hasSize(1);
        send(command); // the broker redelivers after the "crash"
        assertThat(awaitReplies(payment, 2)).hasSize(2);
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
    }

    @Test
    @DisplayName(
            "REQ-PF-021 a command that contradicts the stored decision is dead-lettered: no reply, inbox rolled back")
    void contradictingCommandGoesToDeadLetter() {
        apply(check(payment, payer, 1_000));
        long outboxBefore = outboxRows();
        Envelope contradiction = check(payment, payer, 999);

        send(contradiction);

        Awaitility.await()
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(kafka.readRaw(Topics.deadLetter(Topics.RISK_COMMANDS)).stream()
                                .map(ConsumerRecord::value)
                                .filter(v ->
                                        v.contains(contradiction.messageId().toString())))
                        .hasSize(1));
        assertThat(jdbc.queryForObject(
                        "select count(*) from inbox where message_id = ?", Long.class, contradiction.messageId()))
                .as("inbox row rolled back")
                .isZero();
        assertThat(outboxRows()).as("no reply queued").isEqualTo(outboxBefore);
        assertThat(decision(payment)).containsEntry("amount", 1_000L);
        assertThat(dailyTotal(payer)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("REQ-PF-021 a record that is not a risk command is dead-lettered")
    void garbageGoesToDeadLetter() {
        String garbage = "{\"not\":\"a message " + payment + "\"}";

        kafka.produceRaw(Topics.RISK_COMMANDS, payment.toString(), garbage);

        Awaitility.await()
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(kafka.readRaw(Topics.deadLetter(Topics.RISK_COMMANDS)).stream()
                                .map(ConsumerRecord::value)
                                .filter(v -> v.contains(payment.toString())))
                        .hasSize(1));
        assertThat(inboxRows()).isZero();
        assertThat(decisionRows(payment)).isZero();
    }
}
