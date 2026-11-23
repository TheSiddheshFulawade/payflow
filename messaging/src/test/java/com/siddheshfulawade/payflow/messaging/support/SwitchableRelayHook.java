package com.siddheshfulawade.payflow.messaging.support;

import com.siddheshfulawade.payflow.messaging.RelayHook;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Simulates the relay process dying after Kafka acked but before sent_at is stored. */
public final class SwitchableRelayHook implements RelayHook {

    private final AtomicBoolean crashNext = new AtomicBoolean();

    public void crashOnNextRelay() {
        crashNext.set(true);
    }

    public void reset() {
        crashNext.set(false);
    }

    @Override
    public void afterSendBeforeMarkSent(List<UUID> outboxIds) {
        if (crashNext.getAndSet(false)) {
            throw new IllegalStateException("simulated relay crash after send");
        }
    }
}
