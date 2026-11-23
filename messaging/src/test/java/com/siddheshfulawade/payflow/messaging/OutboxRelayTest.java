package com.siddheshfulawade.payflow.messaging;

import static com.siddheshfulawade.payflow.messaging.support.Fixtures.reserve;
import static com.siddheshfulawade.payflow.messaging.support.Fixtures.risk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.Topics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class OutboxRelayTest extends MessagingIntegrationTest {

    @Test
    void REQ_PF_020_relay_publishes_keyed_by_payment_to_the_right_topic_and_marks_sent() {
        UUID paymentId = UUID.randomUUID();
        Envelope envelope = risk(paymentId);
        UUID outboxId = appendCommitted(envelope);

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(isSent(outboxId)).isTrue();
        List<ConsumerRecord<String, String>> records = kafka.readRaw(Topics.RISK_COMMANDS).stream()
                .filter(r -> paymentId.toString().equals(r.key()))
                .toList();
        assertThat(records).hasSize(1);
        assertThat(new MessageCodec().decode(records.get(0).value())).isEqualTo(envelope);
        assertThat(relay.relayOnce()).as("nothing left to send").isZero();
    }

    @Test
    void REQ_PF_020_two_relays_running_together_send_every_row_exactly_once() throws Exception {
        List<Envelope> envelopes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            envelopes.add(risk(UUID.randomUUID()));
        }
        inTransaction(() -> {
            envelopes.forEach(outbox::append);
            return null;
        });
        Set<UUID> expected = new HashSet<>();
        envelopes.forEach(e -> expected.add(e.messageId()));

        AtomicInteger total = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int n;
                    while ((n = relay.relayOnce()) > 0) {
                        total.addAndGet(n);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(total.get()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from outbox where sent_at is null", Integer.class))
                .isZero();
        MessageCodec codec = new MessageCodec();
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<UUID> seen = kafka.readRaw(Topics.RISK_COMMANDS).stream()
                    .map(r -> codec.decode(r.value()).messageId())
                    .filter(expected::contains)
                    .toList();
            assertThat(seen)
                    .as("each message id once, no double send")
                    .hasSize(200)
                    .doesNotHaveDuplicates();
        });
    }

    /**
     * The key test. The relay dies after Kafka has the message and before sent_at is stored. The restarted relay
     * sends it again. The consumer must still apply it once and only one reply (one messageId) may exist.
     */
    @Test
    void REQ_PF_020_crash_after_send_before_mark_sent_redelivers_but_applies_once() {
        UUID paymentId = UUID.randomUUID();
        Envelope command = reserve(paymentId);
        UUID commandOutboxId = appendCommitted(command);

        hook.crashOnNextRelay();
        assertThatThrownBy(relay::relayOnce).isInstanceOf(RuntimeException.class);

        assertThat(isSent(commandOutboxId))
                .as("sent_at rolled back with the crash")
                .isFalse();
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(effectRows(paymentId)).isEqualTo(1));
        assertThat(kafka.envelopes(Topics.WALLET_COMMANDS, paymentId))
                .as("Kafka already has it once")
                .hasSize(1);

        // "restarted" relay: hook no longer crashes. Poll relayOnce so it also sends the reply rows.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(kafka.envelopes(Topics.WALLET_COMMANDS, paymentId)).hasSize(2);
            assertThat(kafka.envelopes(Topics.WALLET_REPLIES, paymentId)).hasSizeGreaterThanOrEqualTo(2);
        });

        assertThat(isSent(commandOutboxId)).isTrue();
        assertThat(kafka.envelopes(Topics.WALLET_COMMANDS, paymentId))
                .extracting(Envelope::messageId)
                .containsOnly(command.messageId());
        assertThat(effectRows(paymentId)).as("effect applied exactly once").isEqualTo(1);
        assertThat(kafka.envelopes(Topics.WALLET_REPLIES, paymentId))
                .extracting(Envelope::messageId)
                .as("one reply identity, copies allowed")
                .hasSize(2)
                .containsOnly(
                        kafka.envelopes(Topics.WALLET_REPLIES, paymentId).get(0).messageId());
        assertThat(count("inbox")).isEqualTo(1);
    }
}
