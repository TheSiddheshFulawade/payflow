package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Replies travel through the real broker: wallet.replies and risk.replies in, commands out through the outbox. */
class ReplyListenerTest extends PaymentIntegrationTest {

    private void send(String topic, Envelope message) {
        kafka.produceRaw(topic, message.paymentId().toString(), codec.encode(message));
    }

    private void awaitState(UUID id, String expected) {
        Awaitility.await()
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(state(id)).isEqualTo(expected));
    }

    private void awaitDeadLetter(String topic, String marker) {
        Awaitility.await()
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(kafka.readRaw(Topics.deadLetter(topic)).stream()
                                .map(ConsumerRecord::value)
                                .filter(v -> v != null && v.contains(marker)))
                        .hasSize(1));
    }

    @Test
    @DisplayName("REQ-PF-010 the saga runs end to end from the reply topics: reserved, approved, captured, completed")
    void happyPathThroughKafka() {
        Payment p = newPayment();

        send(Topics.WALLET_REPLIES, reply(new FundsReserved(p.id())));
        awaitState(p.id(), "RISK_CHECKING");
        assertThat(onlyOutbox(p.id(), CheckRisk.class).payload()).isEqualTo(new CheckRisk(p.id(), "alice", 500, "INR"));

        send(Topics.RISK_REPLIES, reply(new RiskApproved(p.id())));
        awaitState(p.id(), "CAPTURING");
        assertThat(onlyOutbox(p.id(), CaptureFunds.class).payload())
                .isEqualTo(new CaptureFunds(p.id(), "alice", "bob", 500, "INR"));

        send(Topics.WALLET_REPLIES, reply(new FundsCaptured(p.id())));
        awaitState(p.id(), "COMPLETED");
        assertThat(outboxCount(p.id())).isEqualTo(3);
        assertThat(deadline(p.id())).isNull();
    }

    @Test
    @DisplayName("REQ-PF-010 a wallet rejection from the reply topic ends REJECTED")
    void rejectionThroughKafka() {
        Payment p = newPayment();

        send(Topics.WALLET_REPLIES, reply(new FundsRejected(p.id(), "INSUFFICIENT_FUNDS")));

        awaitState(p.id(), "REJECTED");
        assertThat(outboxCount(p.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 the same reply delivered three times by Kafka has one effect")
    void duplicateDeliveryThroughKafka() {
        Payment p = newPayment();
        Envelope reserved = reply(new FundsReserved(p.id()));

        send(Topics.WALLET_REPLIES, reserved);
        send(Topics.WALLET_REPLIES, reserved);
        send(Topics.WALLET_REPLIES, resent(reserved));

        awaitState(p.id(), "RISK_CHECKING");
        // all three records are consumed in order on one partition: stay stable for a while after the first effect
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    assertThat(version(p.id())).isEqualTo(2);
                    assertThat(outboxCount(p.id())).isEqualTo(2);
                    assertThat(eventCount(p.id())).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("REQ-PF-021 a reply for an unknown payment goes to the dead-letter topic and leaves no inbox row")
    void unknownPaymentGoesToDeadLetter() {
        Envelope orphan = reply(new FundsReserved(UUID.randomUUID()));

        send(Topics.WALLET_REPLIES, orphan);

        awaitDeadLetter(Topics.WALLET_REPLIES, orphan.messageId().toString());
        assertThat(count("select count(*) from inbox where message_id = ?", orphan.messageId()))
                .isZero();
        assertThat(count("select count(*) from outbox"))
                .as("nothing queued by the poison reply")
                .isZero();
    }

    @Test
    @DisplayName("REQ-PF-021 a command that lands on a reply topic is dead-lettered without effect")
    void commandOnReplyTopicGoesToDeadLetter() {
        Payment p = newPayment();
        Envelope command = Envelope.of(new ReserveFunds(p.id(), "alice", 500, "INR"), 1, Clock.systemUTC());
        Snapshot before = snapshotAll();

        send(Topics.RISK_REPLIES, command);

        awaitDeadLetter(Topics.RISK_REPLIES, command.messageId().toString());
        assertThat(snapshotAll()).isEqualTo(before);
    }

    @Test
    @DisplayName("REQ-PF-021 a record that is not a message at all is dead-lettered and does not block the partition")
    void garbageGoesToDeadLetterAndTheNextReplyStillRuns() {
        String garbage = "{not-json-" + UUID.randomUUID();
        Payment p = newPayment();

        kafka.produceRaw(Topics.WALLET_REPLIES, p.id().toString(), garbage);
        send(Topics.WALLET_REPLIES, reply(new FundsReserved(p.id())));

        awaitDeadLetter(Topics.WALLET_REPLIES, garbage);
        awaitState(p.id(), "RISK_CHECKING");
    }

    @Test
    @DisplayName(
            "REQ-PF-022 a reply whose transaction failed is retried (the error handler retries about 4.5 s) and applied once the failure is gone")
    void failedTransactionIsRedelivered() {
        Payment p = newPayment();
        makeOutboxInsertFail("risk.commands");

        send(Topics.WALLET_REPLIES, reply(new FundsReserved(p.id())));
        Awaitility.await()
                .during(Duration.ofSeconds(1))
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(state(p.id())).isEqualTo("RESERVING"));
        dropFailureTriggers();

        awaitState(p.id(), "RISK_CHECKING");
        assertThat(outbox(p.id()).stream().filter(e -> e.payload() instanceof CheckRisk))
                .hasSize(1);
    }
}
