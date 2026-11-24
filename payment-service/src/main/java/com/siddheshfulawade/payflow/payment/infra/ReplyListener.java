package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.app.ReplyHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
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
     * @param value null for a record without a value: the codec makes it a poison message (dead-letter topic)
     */
    @KafkaListener(
            topics = {Topics.WALLET_REPLIES, Topics.RISK_REPLIES},
            groupId = "payment-service")
    void onReply(@Payload(required = false) String value) {
        consumer.handle(codec.decode(value), handler::handle);
    }
}
