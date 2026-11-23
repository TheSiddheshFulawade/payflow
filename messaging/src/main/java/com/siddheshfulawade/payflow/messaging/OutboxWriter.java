package com.siddheshfulawade.payflow.messaging;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.Topics;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes an outgoing message to the outbox. Never talks to Kafka: the {@link OutboxRelay} does that later. */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final MessageCodec codec;

    public OutboxWriter(JdbcClient jdbc, MessageCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    /**
     * Appends the message to the caller's transaction and returns the outbox row id.
     *
     * <p>MANDATORY: the row must commit or roll back with the state change it announces. Called without a
     * transaction it throws {@code IllegalTransactionStateException} instead of writing an orphan message.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(Envelope envelope) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into outbox(id, topic, message_key, payload) values (:id, :topic, :key, :payload)")
                .param("id", id)
                .param("topic", Topics.topicFor(envelope.payload()))
                // key = paymentId: one partition per payment, so its messages stay in order
                .param("key", envelope.paymentId().toString())
                .param("payload", codec.encode(envelope))
                .update();
        return id;
    }
}
