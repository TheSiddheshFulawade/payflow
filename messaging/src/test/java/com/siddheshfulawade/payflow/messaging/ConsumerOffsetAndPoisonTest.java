package com.siddheshfulawade.payflow.messaging;

import static com.siddheshfulawade.payflow.messaging.support.Fixtures.reserve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.contracts.Topics;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsumerOffsetAndPoisonTest extends MessagingIntegrationTest {

    @Test
    void REQ_PF_022_failed_transaction_does_not_commit_the_offset_so_the_message_is_retried_and_applied_once() {
        UUID paymentId = UUID.randomUUID();
        effect.failNextCalls(1);
        appendCommitted(reserve(paymentId));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(effectRows(paymentId)).isEqualTo(1);
        });

        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(effectRows(paymentId)).as("never applied twice").isEqualTo(1);
            assertThat(effect.invocations()).as("failed once, then succeeded").isEqualTo(2);
            assertThat(count("inbox")).isEqualTo(1);
        });
        assertThat(kafka.readRaw(Topics.deadLetter(Topics.WALLET_COMMANDS)))
                .as("a transient failure is retried, not parked")
                .noneMatch(r -> r.value().contains(paymentId.toString()));
    }

    @Test
    void REQ_PF_022_poison_record_is_parked_on_the_dlt_once_and_the_listener_keeps_consuming() {
        String marker = "poison-" + UUID.randomUUID();
        String malformed = "{\"" + marker + "\": ";
        kafka.produceRaw(Topics.WALLET_COMMANDS, "k-" + marker, malformed);

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(kafka.readRaw(Topics.deadLetter(Topics.WALLET_COMMANDS)))
                        .anyMatch(r -> malformed.equals(r.value())));
        assertThat(listener.delivered().stream().filter(malformed::equals))
                .as("not retried")
                .hasSize(1);

        UUID paymentId = UUID.randomUUID();
        appendCommitted(reserve(paymentId));
        await().atMost(TIMEOUT).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(effectRows(paymentId)).isEqualTo(1);
        });
    }
}
