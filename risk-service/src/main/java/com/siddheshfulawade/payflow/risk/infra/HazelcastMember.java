package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Starts a member that knows an owner for every partition before anyone can use it. */
final class HazelcastMember {

    static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    static HazelcastInstance start(Config config, Duration timeout) {
        HazelcastInstance member = Hazelcast.newHazelcastInstance(config);
        try {
            awaitPartitionOwners(member, timeout);
            return member;
        } catch (RuntimeException e) {
            // it never becomes a bean, so nothing else would stop it, and its threads keep the JVM alive
            member.getLifecycleService().terminate();
            throw e;
        }
    }

    /**
     * Hazelcast assigns partitions at the first use. Then the first checks would race the table to the other member,
     * which drops a backup for a partition it does not know yet, without an ack: those checks wait out their timeout.
     */
    private static void awaitPartitionOwners(HazelcastInstance member, Duration timeout) {
        var partitions = member.getPartitionService().getPartitions();
        long start = System.nanoTime();
        // asking for an owner is what makes the cluster assign the partitions
        while (partitions.stream().anyMatch(partition -> partition.getOwner() == null)) {
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            if (waited.compareTo(timeout) >= 0 || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Hazelcast: a partition still has no owner after " + waited.toMillis()
                        + " ms (limit " + timeout.toMillis() + " ms); the member does not start");
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    private HazelcastMember() {}
}
