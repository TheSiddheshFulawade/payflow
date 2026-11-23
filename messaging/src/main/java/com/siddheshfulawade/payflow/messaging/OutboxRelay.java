package com.siddheshfulawade.payflow.messaging;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes unsent outbox rows to Kafka and marks them sent.
 *
 * <p>Delivery is at least once: if the process dies after Kafka acked and before {@code sent_at} commits, the
 * rows stay unsent and go out again. Consumers dedupe by message id (inbox), so that is safe.
 *
 * <p>No row is unsendable, so there is no {@code failed_at} parking: every payload is an encoded {@code Envelope},
 * and the records are bounded at construction (amount, currency, 64-char ids and texts), so a payload is a few
 * hundred bytes, far below the codec's 64 KiB limit and the broker's 1 MB default. A failed batch is therefore a
 * transient fault (broker, network) and is simply retried; it cannot block the queue for good.
 */
public class OutboxRelay {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final KafkaTemplate<String, String> kafka;
    private final RelayHook hook;
    private final int batchSize;
    private final Duration ackTimeout;

    public OutboxRelay(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            KafkaTemplate<String, String> kafka,
            RelayHook hook,
            int batchSize,
            Duration ackTimeout) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.kafka = kafka;
        this.hook = hook;
        this.batchSize = batchSize;
        this.ackTimeout = ackTimeout;
    }

    /**
     * Sends one batch (at most {@code batch-size} rows) in one database transaction.
     *
     * @return how many rows were sent; 0 when nothing was waiting
     * @throws RuntimeException if Kafka did not ack every row in time; the whole batch stays unsent
     */
    public int relayOnce() {
        Integer sent = transaction.execute(status -> relayBatch());
        return sent == null ? 0 : sent;
    }

    private int relayBatch() {
        // FOR UPDATE SKIP LOCKED: each relay claims rows no other relay holds, so two relays never send the same row
        List<Pending> batch = jdbc.sql("""
                        select id, topic, message_key, payload
                        from outbox
                        where sent_at is null
                        order by created_at
                        limit :limit
                        for update skip locked""")
                .param("limit", batchSize)
                .query((rs, n) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("payload")))
                .list();
        if (batch.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<?>> acks = batch.stream()
                .<CompletableFuture<?>>map(m -> kafka.send(m.topic(), m.key(), m.payload()))
                .toList();
        // sent_at only after every ack (acks=all): a row is never marked sent while Kafka might not have it
        awaitAll(acks);
        List<UUID> ids = batch.stream().map(Pending::id).toList();
        hook.afterSendBeforeMarkSent(ids);
        jdbc.sql("update outbox set sent_at = now() where id in (:ids)")
                .param("ids", ids)
                .update();
        return batch.size();
    }

    private void awaitAll(List<CompletableFuture<?>> acks) {
        try {
            CompletableFuture.allOf(acks.toArray(new CompletableFuture<?>[0]))
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Kafka acks; batch stays unsent", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not ack the batch; it stays unsent and is retried", e);
        }
    }

    private record Pending(UUID id, String topic, String key, String payload) {}
}
