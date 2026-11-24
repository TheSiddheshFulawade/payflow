package com.siddheshfulawade.payflow.messaging;

import static com.siddheshfulawade.payflow.messaging.support.Fixtures.reserve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.contracts.Topics;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Over a real broker: a failure the sender repairs blocks nothing, any other failure keeps its 10 deliveries. */
class RetriedBySenderIntegrationTest extends MessagingIntegrationTest {

    private boolean parked(UUID paymentId) {
        return kafka.readRaw(Topics.deadLetter(Topics.WALLET_COMMANDS)).stream()
                .anyMatch(r -> r.value().contains(paymentId.toString()));
    }

    @Test
    void REQ_PF_022_retried_by_sender_failure_is_delivered_once_then_parked_and_the_listener_moves_on() {
        UUID failing = UUID.randomUUID();
        effect.failNextCalls(1, () -> new RetriedBySenderException("the sender re-sends", null));
        appendCommitted(reserve(failing));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(parked(failing)).isTrue();
        });
        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(effect.invocations()).as("one delivery, no retries").isEqualTo(1);
            assertThat(effectRows(failing)).as("its effect rolled back").isZero();
            assertThat(count("inbox")).as("its inbox row rolled back").isZero();
        });

        UUID next = UUID.randomUUID();
        appendCommitted(reserve(next));
        await().atMost(TIMEOUT).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(effectRows(next)).isEqualTo(1);
        });
    }

    @Test
    void REQ_PF_022_other_failures_are_still_delivered_ten_times_before_they_are_parked() {
        UUID failing = UUID.randomUUID();
        effect.failNextCalls(100);
        appendCommitted(reserve(failing));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            relay.relayOnce();
            assertThat(parked(failing)).isTrue();
        });

        assertThat(effect.invocations()).isEqualTo(10);
        assertThat(effectRows(failing)).isZero();
    }
}
