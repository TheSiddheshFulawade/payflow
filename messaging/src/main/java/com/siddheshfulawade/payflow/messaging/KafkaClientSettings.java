package com.siddheshfulawade.payflow.messaging;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Kafka client settings every service gets, applied on top of Boot's {@code spring.kafka.*}. "Required" settings are
 * part of the delivery guarantees and always win; "defaults" are tunables a service may override.
 */
final class KafkaClientSettings {

    static final Map<String, Object> PRODUCER_REQUIRED = Map.of(
            // every in-sync replica has the record before the relay marks the row sent
            ProducerConfig.ACKS_CONFIG,
            "all",
            // the producer's own retries neither duplicate nor reorder records within a partition
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
            true,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class);

    static final Map<String, Object> PRODUCER_DEFAULTS = Map.of(
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000,
            // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms (default 30 s would break that)
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000,
            // send() waits at most 10 s for metadata while the broker is down, so a relay run fails fast
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);

    static final Map<String, Object> CONSUMER_REQUIRED = Map.of(
            // offsets are committed by the container after the listener (and its DB transaction) returned
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            false,
            // harmless without Kafka transactions; never read an aborted record if one ever appears
            ConsumerConfig.ISOLATION_LEVEL_CONFIG,
            "read_committed",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);

    static final Map<String, Object> CONSUMER_DEFAULTS = Map.of(
            // a small poll is processed well within max.poll.interval.ms, even with retries
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
            50,
            // a new consumer group starts at the oldest record, so nothing sent before its first start is skipped
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest");

    /** The changes to apply to {@code current}: missing defaults, then every required setting. */
    static Map<String, Object> updates(
            Map<String, Object> current, Map<String, Object> defaults, Map<String, Object> required) {
        Map<String, Object> updates = new HashMap<>();
        defaults.forEach((key, value) -> {
            if (!current.containsKey(key)) {
                updates.put(key, value);
            }
        });
        updates.putAll(required);
        return updates;
    }

    private KafkaClientSettings() {}
}
