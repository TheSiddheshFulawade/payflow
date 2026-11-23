package com.siddheshfulawade.payflow.messaging.support;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.kafka.annotation.KafkaListener;

/** A wallet-like consumer: decode, then run the effect through the idempotent consumer. */
public class WalletCommandListener {

    private final IdempotentConsumer consumer;
    private final TestEffect effect;
    private final MessageCodec codec = new MessageCodec();
    private final List<String> delivered = new CopyOnWriteArrayList<>();

    public WalletCommandListener(IdempotentConsumer consumer, TestEffect effect) {
        this.consumer = consumer;
        this.effect = effect;
    }

    @KafkaListener(topics = Topics.WALLET_COMMANDS, groupId = "test-wallet")
    public void onCommand(String value) {
        delivered.add(value);
        Envelope envelope = codec.decode(value);
        consumer.handle(envelope, effect::apply);
    }

    /** Every raw value the listener was handed, including redeliveries. */
    public List<String> delivered() {
        return delivered;
    }
}
