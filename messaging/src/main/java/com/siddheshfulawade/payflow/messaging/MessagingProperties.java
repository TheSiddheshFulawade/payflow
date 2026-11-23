package com.siddheshfulawade.payflow.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code payflow.messaging.*} settings. The Kafka client itself is configured with Boot's {@code spring.kafka.*}.
 *
 * @param relay the outbox relay
 * @param replicas replication factor of the topics this library declares (1 locally, 3 on a real cluster)
 */
@ConfigurationProperties("payflow.messaging")
public record MessagingProperties(
        @DefaultValue Relay relay, @DefaultValue("1") int replicas) {

    /**
     * @param enabled whether this instance runs the relay on a schedule (tests turn it off and call it directly)
     * @param interval pause between relay runs
     * @param batchSize rows per relay transaction
     * @param ackTimeout how long a batch waits for Kafka's acks. Longer than the producer's delivery.timeout.ms
     *     (30 s), so normally the producer reports the real error first; this only bounds a hung client.
     */
    public record Relay(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("200ms") Duration interval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("35s") Duration ackTimeout) {}
}
