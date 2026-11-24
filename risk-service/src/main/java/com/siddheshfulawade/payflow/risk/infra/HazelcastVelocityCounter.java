package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** One velocity window per payer in a map shared by all members; changed only through the entry processor. */
@Component
public class HazelcastVelocityCounter implements VelocityCounter {

    public static final String MAP_NAME = "velocity";

    /** The caller holds row locks and a Kafka partition while it waits, so the wait is short. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final IMap<String, VelocityWindow> windows;
    private final RiskLimits limits;

    public HazelcastVelocityCounter(HazelcastInstance hazelcast, RiskLimits limits) {
        this.windows = hazelcast.getMap(MAP_NAME);
        this.limits = limits;
    }

    /** Fails closed: an unreachable, failing or slow cluster throws, and the caller's transaction rolls back. */
    @Override
    public boolean allow(String payer, UUID paymentId, Instant now) {
        var check = new VelocityEntryProcessor(
                paymentId,
                now.toEpochMilli(),
                limits.velocity(),
                limits.window().toMillis());
        // The hard bound: Hazelcast's own call timeout waits on as long as the owner still sends heartbeats. A check
        // that runs after we gave up is harmless: the retry asks for the same payment id.
        return windows.submitToKey(payer, check)
                .toCompletableFuture()
                .orTimeout(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();
    }
}
