package com.siddheshfulawade.payflow.wallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.OutboxRelay;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Commands travel through the real broker: wallet.commands in, wallet.replies out (relay run by the test). */
class WalletCommandListenerTest extends WalletIntegrationTest {

    @Autowired
    OutboxRelay relay;

    private void send(Envelope command) {
        kafka.produceRaw(Topics.WALLET_COMMANDS, command.paymentId().toString(), codec.encode(command));
    }

    /** Relays the outbox until the payment has at least {@code count} replies on wallet.replies. */
    private List<ConsumerRecord<String, String>> awaitReplies(UUID payment, int count) {
        Awaitility.await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(repliesFor(payment)).hasSizeGreaterThanOrEqualTo(count);
        });
        return repliesFor(payment);
    }

    private List<ConsumerRecord<String, String>> repliesFor(UUID payment) {
        return kafka.readRaw(Topics.WALLET_REPLIES).stream()
                .filter(r -> payment.toString().equals(r.key()))
                .toList();
    }

    @Test
    @DisplayName(
            "REQ-PF-030 ReserveFunds on wallet.commands is answered on wallet.replies, keyed by payment, same attempt")
    void reserveEndToEnd() {
        UUID payment = UUID.randomUUID();
        wallet("alice", 1_000);

        send(reserve(payment, "alice", 300, 2));

        List<ConsumerRecord<String, String>> replies = awaitReplies(payment, 1);
        Envelope reply = codec.decode(replies.get(0).value());
        assertThat(replies.get(0).key()).isEqualTo(payment.toString());
        assertThat(reply.payload()).isEqualTo(new FundsReserved(payment));
        assertThat(reply.attempt()).isEqualTo(2);
        assertBalances("alice", 700, 300);
    }

    @Test
    @DisplayName(
            "REQ-PF-021 the same ReserveFunds delivered twice has one effect and its reply is re-sent with the same message id")
    void duplicateDeliveryHasOneEffect() {
        UUID payment = UUID.randomUUID();
        wallet("alice", 1_000);
        Envelope command = reserve(payment, "alice", 300);

        send(command);
        send(command);

        List<ConsumerRecord<String, String>> replies = awaitReplies(payment, 2);
        List<Envelope> decoded =
                replies.stream().map(r -> codec.decode(r.value())).toList();
        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).messageId()).isEqualTo(decoded.get(1).messageId());
        assertThat(decoded).allSatisfy(e -> assertThat(e.payload()).isEqualTo(new FundsReserved(payment)));
        assertBalances("alice", 700, 300);
        assertThat(ledger(payment)).hasSize(1);
        assertThat(holdRows(payment)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-021 a sweeper re-send (new message id) over Kafka is answered but holds only once")
    void resentCommandOverKafkaHoldsOnce() {
        UUID payment = UUID.randomUUID();
        wallet("alice", 1_000);
        Envelope command = reserve(payment, "alice", 300);

        send(command);
        send(resent(command));

        List<ConsumerRecord<String, String>> replies = awaitReplies(payment, 2);
        assertThat(replies.stream().map(r -> codec.decode(r.value()).payload()))
                .allSatisfy(p -> assertThat(p).isEqualTo(new FundsReserved(payment)));
        assertBalances("alice", 700, 300);
        assertThat(ledger(payment)).hasSize(1);
    }

    @Test
    @DisplayName("REQ-PF-021 a command that mismatches its hold is dead-lettered: no reply, inbox rolled back")
    void mismatchingCommandGoesToDeadLetter() {
        UUID payment = UUID.randomUUID();
        wallet("alice", 1_000);
        apply(reserve(payment, "alice", 300));
        long outboxBefore = jdbc.queryForObject("select count(*) from outbox", Long.class);
        Envelope mismatch = reserve(payment, "alice", 999);

        send(mismatch);

        Awaitility.await()
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(kafka.readRaw(Topics.deadLetter(Topics.WALLET_COMMANDS)).stream()
                                .map(ConsumerRecord::value)
                                .filter(v -> v.contains(mismatch.messageId().toString())))
                        .hasSize(1));
        assertThat(jdbc.queryForObject(
                        "select count(*) from inbox where message_id = ?", Long.class, mismatch.messageId()))
                .as("inbox row rolled back")
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class))
                .as("no reply queued")
                .isEqualTo(outboxBefore);
        assertBalances("alice", 700, 300);
        assertThat(ledger(payment)).hasSize(1);
    }
}
