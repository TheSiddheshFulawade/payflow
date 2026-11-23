package com.siddheshfulawade.payflow.messaging;

import static com.siddheshfulawade.payflow.messaging.support.Fixtures.reserve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotentConsumerTest extends MessagingIntegrationTest {

    @Autowired
    IdempotentConsumer consumer;

    private final MessageCodec codec = new MessageCodec();

    @Test
    void REQ_PF_021_same_envelope_twice_applies_once_and_resends_the_stored_reply() {
        UUID paymentId = UUID.randomUUID();
        Envelope command = reserve(paymentId);

        IdempotentConsumer.Outcome first = consumer.handle(command, effect::apply);
        UUID firstReplyOutboxId = jdbc.queryForObject("select id from outbox", UUID.class);
        IdempotentConsumer.Outcome second = consumer.handle(command, effect::apply);

        assertThat(first).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(second).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        assertThat(effect.invocations()).isEqualTo(1);
        assertThat(effectRows(paymentId)).isEqualTo(1);
        assertThat(count("inbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select reply_outbox_id from inbox where message_id = ?", UUID.class, command.messageId()))
                .isEqualTo(firstReplyOutboxId);

        List<Map<String, Object>> rows = jdbc.queryForList("select id, payload from outbox");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("id")).doesNotHaveDuplicates();
        List<Envelope> replies =
                rows.stream().map(r -> codec.decode((String) r.get("payload"))).toList();
        assertThat(replies)
                .extracting(Envelope::messageId)
                .containsOnly(replies.get(0).messageId());
        assertThat(replies).allSatisfy(r -> assertThat(r.payload()).isEqualTo(new FundsReserved(paymentId)));
    }

    @Test
    void REQ_PF_021_duplicate_of_a_message_that_produced_no_reply_sends_nothing() {
        Envelope command = reserve(UUID.randomUUID());

        consumer.handle(command, effect::applyWithoutReply);
        IdempotentConsumer.Outcome second = consumer.handle(command, effect::applyWithoutReply);

        assertThat(second).isEqualTo(IdempotentConsumer.Outcome.DUPLICATE);
        assertThat(effect.invocations()).isEqualTo(1);
        assertThat(count("outbox")).isZero();
    }

    @Test
    void REQ_PF_021_failed_effect_rolls_back_the_inbox_row_so_a_retry_applies() {
        UUID paymentId = UUID.randomUUID();
        Envelope command = reserve(paymentId);
        effect.failNextCalls(1);

        assertThatThrownBy(() -> consumer.handle(command, effect::apply)).isInstanceOf(RuntimeException.class);

        assertThat(count("inbox"))
                .as("inbox row is in the same transaction as the effect")
                .isZero();
        assertThat(effectRows(paymentId)).isZero();
        assertThat(count("outbox")).isZero();

        assertThat(consumer.handle(command, effect::apply)).isEqualTo(IdempotentConsumer.Outcome.APPLIED);
        assertThat(effectRows(paymentId)).isEqualTo(1);
    }
}
