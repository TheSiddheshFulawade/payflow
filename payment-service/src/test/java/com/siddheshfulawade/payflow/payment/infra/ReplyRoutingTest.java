package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.siddheshfulawade.payflow.contracts.CaptureFailed;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.app.ReplyHandler;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * A reply is only believed on the topic its type belongs to and under its own payment id as Kafka key (the key decides
 * the partition, so a wrong key also breaks the per-payment ordering). Anything else is poison: dead-lettered at once,
 * and the idempotent consumer (inbox, state change) is never reached. Unit test, no Docker.
 */
class ReplyRoutingTest {

    // with letters, so that its upper-case form is another text
    private static final UUID PAYMENT = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

    private final IdempotentConsumer consumer = mock(IdempotentConsumer.class);
    private final ReplyHandler handler = mock(ReplyHandler.class);
    private final MessageCodec codec = new MessageCodec();
    private final ReplyListener listener = new ReplyListener(codec, consumer, handler);

    private static Stream<Reply> allReplies() {
        return Stream.of(
                new FundsReserved(PAYMENT),
                new FundsRejected(PAYMENT, "INSUFFICIENT_FUNDS"),
                new FundsCaptured(PAYMENT),
                new CaptureFailed(PAYMENT, "payee frozen"),
                new FundsReleased(PAYMENT),
                new RiskApproved(PAYMENT),
                new RiskRejected(PAYMENT, "velocity"));
    }

    private static String otherReplyTopic(String topic) {
        return Topics.WALLET_REPLIES.equals(topic) ? Topics.RISK_REPLIES : Topics.WALLET_REPLIES;
    }

    private String json(Reply payload) {
        return codec.encode(Envelope.of(payload, 1, Clock.systemUTC()));
    }

    private static ConsumerRecord<String, String> record(String topic, String key, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, key, value);
    }

    private void assertPoisonAndUntouched(ConsumerRecord<String, String> record, String... mustNotAppear) {
        assertThatThrownBy(() -> listener.onReply(record))
                .isInstanceOf(PoisonMessageException.class)
                .satisfies(e -> {
                    for (String value : mustNotAppear) {
                        assertThat(e.getMessage())
                                .as("message must not echo a value")
                                .doesNotContain(value);
                    }
                });

        verifyNoInteractions(consumer, handler);
    }

    @ParameterizedTest(name = "{0} on its own topic with its paymentId as key is handled once")
    @MethodSource("allReplies")
    @DisplayName("REQ-PF-021 a reply on its own topic with its paymentId as key reaches the idempotent consumer once")
    void rightTopicAndKeyIsHandled(Reply payload) {
        String json = json(payload);

        listener.onReply(record(Topics.topicFor(payload), PAYMENT.toString(), json));

        ArgumentCaptor<Envelope> handled = ArgumentCaptor.forClass(Envelope.class);
        verify(consumer, times(1)).handle(handled.capture(), any());
        assertThat(handled.getValue()).isEqualTo(codec.decode(json));
        assertThat(handled.getValue().payload()).isEqualTo(payload);
    }

    @ParameterizedTest(name = "{0} on the other reply topic is poison")
    @MethodSource("allReplies")
    @DisplayName("REQ-PF-021 a reply on the wrong reply topic is poison and nothing is handled")
    void wrongTopicIsPoison(Reply payload) {
        String wrongTopic = otherReplyTopic(Topics.topicFor(payload));

        assertPoisonAndUntouched(record(wrongTopic, PAYMENT.toString(), json(payload)), PAYMENT.toString());
    }

    @ParameterizedTest(name = "key \"{0}\" is not the paymentId")
    @MethodSource("wrongKeys")
    @DisplayName("REQ-PF-021 a reply whose Kafka key is not its paymentId is poison and nothing is handled")
    void wrongKeyIsPoison(String key) {
        Reply payload = new FundsReserved(PAYMENT);

        String[] mustNotAppear =
                key.isEmpty() ? new String[] {PAYMENT.toString()} : new String[] {PAYMENT.toString(), key};
        assertPoisonAndUntouched(record(Topics.WALLET_REPLIES, key, json(payload)), mustNotAppear);
    }

    static Stream<String> wrongKeys() {
        return Stream.of(
                UUID.randomUUID().toString(), // another payment's id
                "attacker-key-77",
                "", // empty
                PAYMENT.toString().toUpperCase(), // same uuid, different text: the key must be the canonical id
                " " + PAYMENT, // padded
                PAYMENT + "\n");
    }

    @Test
    @DisplayName("REQ-PF-021 a reply without a Kafka key is poison and nothing is handled")
    void nullKeyIsPoison() {
        assertPoisonAndUntouched(record(Topics.WALLET_REPLIES, null, json(new FundsReserved(PAYMENT))));
    }

    @Test
    @DisplayName("REQ-PF-021 a record without a value is poison and nothing is handled")
    void nullValueIsPoison() {
        assertPoisonAndUntouched(record(Topics.WALLET_REPLIES, PAYMENT.toString(), null));
    }

    @Test
    @DisplayName("REQ-PF-021 the poison text is fixed: the same for every wrong key, and without the key or topic")
    void poisonTextDoesNotDependOnTheValues() {
        String json = json(new FundsReserved(PAYMENT));

        String first = poisonMessage(record(Topics.WALLET_REPLIES, "first-bad-key", json));
        String second = poisonMessage(record(Topics.WALLET_REPLIES, "second-bad-key", json));
        String nullKey = poisonMessage(record(Topics.WALLET_REPLIES, null, json));

        assertThat(second).isEqualTo(first);
        assertThat(nullKey).isEqualTo(first);
        assertThat(first).doesNotContain("bad-key").doesNotContain(PAYMENT.toString());
    }

    private String poisonMessage(ConsumerRecord<String, String> record) {
        Throwable[] thrown = new Throwable[1];
        assertThatThrownBy(() -> {
                    try {
                        listener.onReply(record);
                    } catch (Throwable t) {
                        thrown[0] = t;
                        throw t;
                    }
                })
                .isInstanceOf(PoisonMessageException.class);
        return thrown[0].getMessage();
    }

    @Test
    @DisplayName("REQ-PF-021 the key is checked against the paymentId inside the message, not just against its shape")
    void keyOfAnotherPaymentIsPoisonEvenIfWellFormed() {
        UUID other = UUID.randomUUID();

        assertPoisonAndUntouched(
                record(Topics.RISK_REPLIES, other.toString(), json(new RiskApproved(PAYMENT))),
                other.toString(),
                PAYMENT.toString());
    }
}
