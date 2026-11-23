package com.siddheshfulawade.payflow.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Called by {@link OutboxRelay} after Kafka acked a batch and before {@code sent_at} is stored. Production uses a
 * no-op; tests throw here to simulate the relay dying in exactly that window.
 */
@FunctionalInterface
public interface RelayHook {

    void afterSendBeforeMarkSent(List<UUID> outboxIds);
}
