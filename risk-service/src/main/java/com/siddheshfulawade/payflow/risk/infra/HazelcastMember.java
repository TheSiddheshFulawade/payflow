package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a member. Invariant: before anyone can use it, it knows an owner for every partition; and unless it warned, it
 * has seen the cluster safe (no migration pending, every backup in place).
 *
 * <p>This covers the checks of the member that starts. A member already running serves checks while another one joins,
 * and one of those can still lose its backup ack: it waits out {@link HazelcastVelocityCounter#CALL_TIMEOUT} and throws
 * (fails closed), and the sender re-sends it.
 */
final class HazelcastMember {

    /**
     * One limit for both waits. More than the 10 s after which a hung member is evicted, because then the cluster can
     * become safe without it. A safe check that is running at the limit still ends by itself: Hazelcast waits for the
     * members' answers for {@code hazelcast.graceful.shutdown.max.wait} (30 s) at most. So both waits together end
     * within 60 s, less than the risk step's 65 s of re-sends.
     */
    static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private static final Logger log = LoggerFactory.getLogger(HazelcastMember.class);

    static HazelcastInstance start(Config config, Duration timeout) {
        HazelcastInstance member = Hazelcast.newHazelcastInstance(config);
        try {
            long start = System.nanoTime();
            awaitPartitionOwners(member, start, timeout);
            awaitClusterSafe(member, start, timeout);
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
    private static void awaitPartitionOwners(HazelcastInstance member, long start, Duration timeout) {
        var partitions = member.getPartitionService().getPartitions();
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

    /**
     * Failure considered: a check that starts while the join migrations run may never get its backup ack (one of 40 in
     * a CI run; the lost ack is inferred, not proven).
     *
     * <p>Not safe in time (another member hangs, or migrations are switched off): it starts anyway, because a check
     * that times out fails closed and is re-sent, while a risk member that refuses to start answers nothing. A member
     * that does not answer makes the call return false, and an interrupt ends the wait after the running call. The call
     * throws if this member itself was shut down: nobody can use it, so the start fails.
     */
    private static void awaitClusterSafe(HazelcastInstance member, long start, Duration timeout) {
        while (!member.getPartitionService().isClusterSafe()) {
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            if (waited.compareTo(timeout) >= 0 || Thread.currentThread().isInterrupted()) {
                log.warn(
                        "Hazelcast: the cluster is still not safe after {} ms; the member starts anyway, and its first"
                                + " velocity checks may time out (they fail closed and are re-sent)",
                        waited.toMillis());
                return;
            }
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    private HazelcastMember() {}
}
