package com.siddheshfulawade.payflow.wallet.infra;

import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.wallet.app.WalletCommandHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** wallet.commands in. The reply leaves through the outbox, never from here. */
@Component
class WalletCommandListener {

    private final MessageCodec codec;
    private final IdempotentConsumer consumer;
    private final WalletCommandHandler handler;

    WalletCommandListener(MessageCodec codec, IdempotentConsumer consumer, WalletCommandHandler handler) {
        this.codec = codec;
        this.consumer = consumer;
        this.handler = handler;
    }

    /**
     * Returns only after the database commit, and the container commits the offset only after the return
     * (REQ-PF-022). A crash in between redelivers the record and the inbox turns it into a re-sent reply.
     *
     * @param value null for a record without a value: the codec makes it a poison message (dead-letter topic)
     */
    @KafkaListener(topics = Topics.WALLET_COMMANDS, groupId = "wallet-service")
    void onCommand(@Payload(required = false) String value) {
        consumer.handle(codec.decode(value), handler::handle);
    }
}
