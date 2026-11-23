package com.siddheshfulawade.payflow.messaging;

import static com.siddheshfulawade.payflow.messaging.support.Fixtures.reserve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.Topics;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxWriterTest extends MessagingIntegrationTest {

    @Test
    void REQ_PF_020_business_row_and_outbox_row_commit_together() {
        UUID paymentId = UUID.randomUUID();
        Envelope envelope = reserve(paymentId);

        UUID outboxId = inTransaction(() -> {
            jdbc.update("insert into effect(payment_id, message_id) values (?, ?)", paymentId, envelope.messageId());
            return outbox.append(envelope);
        });

        assertThat(effectRows(paymentId)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select * from outbox where id = ?", outboxId);
        assertThat(row.get("topic")).isEqualTo(Topics.WALLET_COMMANDS);
        assertThat(row.get("message_key")).isEqualTo(paymentId.toString());
        assertThat(row.get("sent_at")).isNull();
        assertThat(new MessageCodec().decode((String) row.get("payload"))).isEqualTo(envelope);
    }

    @Test
    void REQ_PF_020_rollback_leaves_neither_row_and_nothing_reaches_kafka() {
        UUID paymentId = UUID.randomUUID();
        Envelope envelope = reserve(paymentId);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update(
                            "insert into effect(payment_id, message_id) values (?, ?)",
                            paymentId,
                            envelope.messageId());
                    outbox.append(envelope);
                    throw new IllegalStateException("business failure after the append");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(effectRows(paymentId)).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(relay.relayOnce()).isZero();
        assertThat(kafka.envelopes(Topics.WALLET_COMMANDS, paymentId)).isEmpty();
    }

    @Test
    void REQ_PF_020_append_outside_a_transaction_is_refused() {
        assertThatThrownBy(() -> outbox.append(reserve(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("outbox")).isZero();
    }
}
