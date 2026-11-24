package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.app.ReplyHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** wallet.replies and risk.replies in. The next command leaves through the outbox, never from here. */
@Component
class ReplyListener {

    private final MessageCodec codec;
    private final IdempotentConsumer consumer;
    private final ReplyHandler handler;

    ReplyListener(MessageCodec codec, IdempotentConsumer consumer, ReplyHandler handler) {
        this.codec = codec;
        this.consumer = consumer;
        this.handler = handler;
    }

    /**
     * Returns only after the database commit, and the container commits the offset only after the return
     * (REQ-PF-022). A crash in between redelivers the record and the inbox turns it into a no-op.
     *
     * <p>Before anything is recorded, the record must be where its message belongs:
     *
     * <ul>
     *   <li>topic: the per-topic ACLs of ADR-0002 (only wallet-service writes wallet.replies, ...) protect nothing if
     *       a RiskApproved is also believed on wallet.replies
     *   <li>key: it picks the partition, so a reply under another key has no order relative to its payment's others
     * </ul>
     *
     * @throws PoisonMessageException if the record has no valid message (no value included), or is misrouted; the
     *     text is fixed and repeats nothing from the record
     */
    @KafkaListener(
            topics = {Topics.WALLET_REPLIES, Topics.RISK_REPLIES},
            groupId = "payment-service")
    void onReply(ConsumerRecord<String, String> record) {
        Envelope message = codec.decode(record.value());
        boolean rightTopic = Topics.topicFor(message.payload()).equals(record.topic());
        boolean rightKey = message.paymentId().toString().equals(record.key());
        if (!rightTopic || !rightKey) {
            throw new PoisonMessageException("message on the wrong topic or under the wrong key");
        }
        consumer.handle(message, handler::handle);
    }
}
