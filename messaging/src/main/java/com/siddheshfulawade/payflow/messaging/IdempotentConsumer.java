package com.siddheshfulawade.payflow.messaging;

import com.siddheshfulawade.payflow.contracts.Envelope;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a message's effect at most once per message id (REQ-PF-021).
 *
 * <p>Call it from a Kafka listener and return normally: the container commits the offset only after this method
 * returned, i.e. after the database commit (REQ-PF-022). If it throws, nothing was committed and the record is
 * delivered again.
 */
public class IdempotentConsumer {

    /** What {@link #handle} did with the message. */
    public enum Outcome {
        /** First delivery: the effect ran and its reply (if any) is in the outbox. */
        APPLIED,
        /** Seen before: no effect; the stored reply (if any) was queued again with the same message id. */
        DUPLICATE
    }

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final OutboxWriter outbox;

    public IdempotentConsumer(JdbcClient jdbc, PlatformTransactionManager transactionManager, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.outbox = outbox;
    }

    /**
     * In one transaction: records the message id, runs {@code effect}, and appends its reply to the outbox.
     *
     * @param effect the business change; it must use the same DataSource so it joins this transaction
     */
    public Outcome handle(Envelope message, Function<Envelope, Optional<Envelope>> effect) {
        return transaction.execute(status -> {
            // inbox row in the same transaction as the effect: both commit or neither does. A concurrent copy
            // blocks on the primary key until this commits, then sees the row and becomes a DUPLICATE.
            int inserted = jdbc.sql("insert into inbox(message_id) values (:id) on conflict (message_id) do nothing")
                    .param("id", message.messageId())
                    .update();
            if (inserted == 0) {
                int resent = resendStoredReply(message.messageId());
                log.info(
                        "duplicate {} {} for payment {}: no effect, {} reply re-sent",
                        message.type(),
                        message.messageId(),
                        message.paymentId(),
                        resent);
                return Outcome.DUPLICATE;
            }
            effect.apply(message).ifPresent(reply -> {
                UUID replyId = outbox.append(reply);
                jdbc.sql("update inbox set reply_outbox_id = :reply where message_id = :id")
                        .param("reply", replyId)
                        .param("id", message.messageId())
                        .update();
            });
            return Outcome.APPLIED;
        });
    }

    /**
     * Queues the stored reply again, byte for byte (same message id, so the receiver dedupes it too). The sender
     * may have missed the first copy, which is why a duplicate command is redelivered at all.
     */
    private int resendStoredReply(UUID messageId) {
        return jdbc.sql("""
                        insert into outbox(id, topic, message_key, payload)
                        select :newId, o.topic, o.message_key, o.payload
                        from inbox i join outbox o on o.id = i.reply_outbox_id
                        where i.message_id = :id""")
                .param("newId", UUID.randomUUID())
                .param("id", messageId)
                .update();
    }
}
