package com.siddheshfulawade.payflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Topics;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * How many times a record is delivered before it is parked on the dead-letter topic, per kind of failure. No broker:
 * the template is a mock, and the handler is the one the services get.
 */
class ErrorHandlerRetryTest {

    private final KafkaTemplate<String, String> kafka = mock();
    private final Consumer<String, String> consumer = mock();
    private final MessageListenerContainer container = mock();
    private final CommonErrorHandler handler = new MessagingAutoConfiguration().payflowErrorHandler(kafka);

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>(Topics.WALLET_COMMANDS, 0, 7L, "key", "value");

    /** What the container does: hands the failure of every delivery to the handler, wrapped as the listener wraps it. */
    private int deliveriesUntilDeadLettered(Exception failure) {
        for (int delivery = 1; delivery <= 30; delivery++) {
            try {
                handler.handleRemaining(
                        new ListenerExecutionFailedException("Listener failed", failure),
                        List.<ConsumerRecord<?, ?>>of(record),
                        consumer,
                        container);
            } catch (RuntimeException seekBack) {
                // the container is told to deliver the record again
            }
            if (deadLetterTopics().size() > 0) {
                return delivery;
            }
        }
        return -1;
    }

    private List<String> deadLetterTopics() {
        return mockingDetails(kafka).getInvocations().stream()
                .map(i -> i.getArguments().length > 0 ? i.getArgument(0, Object.class) : null)
                .filter(ProducerRecord.class::isInstance)
                .map(r -> ((ProducerRecord<?, ?>) r).topic())
                .toList();
    }

    @Test
    @DisplayName(
            "REQ-PF-022 a failure the sender will repair is delivered once, then parked: it never blocks the partition")
    void retriedBySenderIsDeliveredOnce() {
        int deliveries = deliveriesUntilDeadLettered(new RetriedBySenderException("the sender re-sends", null));

        assertThat(deliveries).isEqualTo(1);
        assertThat(deadLetterTopics()).containsExactly(Topics.deadLetter(Topics.WALLET_COMMANDS));
    }

    @Test
    @DisplayName("REQ-PF-022 the cause of a sender-retried failure does not make it retryable")
    void retriedBySenderWithCauseIsDeliveredOnce() {
        int deliveries = deliveriesUntilDeadLettered(
                new RetriedBySenderException("the sender re-sends", new IllegalStateException("cluster down")));

        assertThat(deliveries).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-022 any other failure is still delivered 10 times before it is parked")
    void otherFailuresAreStillRetried() {
        int deliveries = deliveriesUntilDeadLettered(new IllegalStateException("database blip"));

        assertThat(deliveries).isEqualTo(10);
        assertThat(deadLetterTopics()).containsExactly(Topics.deadLetter(Topics.WALLET_COMMANDS));
    }

    @Test
    @DisplayName("REQ-PF-022 a poison message is still parked at the first delivery")
    void poisonIsStillDeliveredOnce() {
        int deliveries = deliveriesUntilDeadLettered(new PoisonMessageException("not a message"));

        assertThat(deliveries).isEqualTo(1);
    }
}
