package com.siddheshfulawade.payflow.risk.infra;

import static com.siddheshfulawade.payflow.risk.infra.HazelcastVelocityClusterTest.awaitClusterSafe;
import static com.siddheshfulawade.payflow.risk.infra.HazelcastVelocityClusterTest.clusterSize;
import static com.siddheshfulawade.payflow.risk.infra.HazelcastVelocityClusterTest.concurrently;
import static com.siddheshfulawade.payflow.risk.infra.HazelcastVelocityClusterTest.freePorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.impl.HazelcastInstanceProxy;
import com.hazelcast.partition.MigrationListener;
import com.hazelcast.partition.MigrationState;
import com.hazelcast.partition.ReplicaMigrationEvent;
import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import com.siddheshfulawade.payflow.risk.app.VelocityUnavailableException;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A member's start, through the production bean method, and checks that run while the cluster is still forming. */
class HazelcastMemberTest {

    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final RiskLimits LIMITS = RiskLimits.defaults();

    private final List<HazelcastInstance> started = new ArrayList<>();
    private String cluster;
    private int[] ports;

    @BeforeEach
    void newCluster() throws IOException {
        cluster = "risk-start-" + UUID.randomUUID();
        ports = freePorts();
    }

    @AfterEach
    void stopMembers() {
        started.forEach(member -> member.getLifecycleService().terminate());
    }

    private HazelcastProperties properties(int index) {
        return new HazelcastProperties(
                cluster, ports[index], List.of("127.0.0.1:" + ports[0], "127.0.0.1:" + ports[1]), "127.0.0.1");
    }

    private Config config(int index) {
        return HazelcastConfigFactory.build(properties(index), LIMITS);
    }

    /** The production path. */
    private HazelcastInstance startMember(int index) {
        return track(new RiskConfig().hazelcastInstance(properties(index), LIMITS));
    }

    private synchronized HazelcastInstance track(HazelcastInstance member) {
        started.add(member);
        return member;
    }

    /** Looks without asking: the public API would itself trigger the assignment. */
    private static boolean everyPartitionAlreadyHasAnOwner(HazelcastInstance member) {
        var partitions = ((HazelcastInstanceProxy) member).getOriginal().node.getPartitionService();
        for (int id = 0; id < partitions.getPartitionCount(); id++) {
            if (partitions.getPartition(id, false).getOwnerOrNull() == null) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("REQ-PF-032 a started member already has the partition table, first member and joining member alike")
    void startedMemberHasThePartitionTable() {
        HazelcastInstance first = startMember(0);
        assertThat(everyPartitionAlreadyHasAnOwner(first)).as("first member").isTrue();

        HazelcastInstance second = startMember(1);
        assertThat(everyPartitionAlreadyHasAnOwner(second)).as("second member").isTrue();
    }

    @Test
    @DisplayName("REQ-PF-032 a lone first member starts promptly and is safe: no backup can exist, none is awaited")
    void loneFirstMemberStartsPromptlyAndIsSafe() {
        long begin = System.nanoTime();
        HazelcastInstance first = startMember(0);
        Duration took = Duration.ofNanos(System.nanoTime() - begin);

        assertThat(first.getPartitionService().isClusterSafe()).isTrue();
        assertThat(took).isLessThan(Duration.ofSeconds(20));
    }

    /** Backups still being copied are the window in which an ack or an operation can be lost. */
    @Test
    @DisplayName("REQ-PF-032 a joining member is handed out only when the cluster is safe: no migration pending,"
            + " every backup in place")
    void joiningMemberIsHandedOutOnlyWhenClusterIsSafe() {
        HazelcastInstance first = startMember(0);
        VelocityCounter onFirst = new HazelcastVelocityCounter(first, LIMITS);
        // data in many partitions, so the backups to copy to the joiner take time
        for (int i = 0; i < 2000; i++) {
            onFirst.allow("payer-" + i, UUID.randomUUID(), NOW);
        }

        HazelcastInstance second = startMember(1);

        // asked once, right after the start returned: no waiting
        assertThat(second.getPartitionService().isClusterSafe())
                .as("joining member sees a safe cluster")
                .isTrue();
        assertThat(first.getPartitionService().isClusterSafe())
                .as("first member sees a safe cluster")
                .isTrue();
    }

    /** Without the table at the start, the first backups could be dropped and up to 16 of these 40 checks timed out. */
    @RepeatedTest(5)
    @DisplayName("REQ-PF-032 40 checks at once on a just started cluster: all answered, exactly 5 allowed")
    void firstChecksOnAFreshClusterAreAnswered() throws Exception {
        VelocityCounter onA = new HazelcastVelocityCounter(startMember(0), LIMITS);
        VelocityCounter onB = new HazelcastVelocityCounter(startMember(1), LIMITS);
        List<Callable<Boolean>> checks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            VelocityCounter member = i % 2 == 0 ? onA : onB;
            UUID payment = UUID.randomUUID();
            checks.add(() -> member.allow("fresh-payer", payment, NOW));
        }

        List<Boolean> results = concurrently(checks);

        assertThat(results.stream().filter(allowed -> allowed).count()).isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-PF-032 a member that gets no partition owners fails its start, says why, and leaves the cluster")
    void startFailsWithoutPartitionOwners() {
        HazelcastInstance first = track(Hazelcast.newHazelcastInstance(config(0)));
        // in this state the cluster never assigns partitions
        first.getCluster().changeClusterState(ClusterState.NO_MIGRATION);

        assertThatThrownBy(() -> track(HazelcastMember.start(config(1), Duration.ofSeconds(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still has no owner")
                .hasMessageContaining("limit 1000 ms");

        Awaitility.await("the failed member is gone").atMost(WAIT).until(() -> clusterSize(first) == 1);
        assertThat(Hazelcast.getAllHazelcastInstances())
                .as("no half-started member is left in this JVM")
                .noneMatch(member -> member.getConfig().getClusterName().equals(cluster) && member != first);
    }

    @Test
    @DisplayName("REQ-PF-032 a member whose cluster does not get safe in time warns once, starts anyway and answers")
    void startsWithOneWarningWhenTheClusterDoesNotGetSafe() {
        HazelcastInstance first = startMember(0);
        // the owners are known, but the joiner never gets its backups
        first.getCluster().changeClusterState(ClusterState.NO_MIGRATION);
        var logger = (Logger) LoggerFactory.getLogger(HazelcastMember.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);

        HazelcastInstance second;
        try {
            second = track(HazelcastMember.start(config(1), Duration.ofSeconds(1)));
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(second.getPartitionService().isClusterSafe()).isFalse();
        assertThat(new HazelcastVelocityCounter(second, LIMITS).allow("payer", UUID.randomUUID(), NOW))
                .isTrue();
        assertThat(logs.list).hasSize(1);
        ILoggingEvent warning = logs.list.get(0);
        assertThat(warning.getLevel()).isEqualTo(Level.WARN);
        assertThat(warning.getFormattedMessage())
                .contains("not safe")
                .doesNotContain("127.0.0.1", cluster, String.valueOf(ports[0]), String.valueOf(ports[1]));
        assertThat(warning.getArgumentArray()).as("the waited ms").hasSize(1);
        assertThat((Long) warning.getArgumentArray()[0]).isGreaterThanOrEqualTo(1000);
    }

    // ---- a member joins a running one that holds counts ----

    private record Check(String payer, UUID payment) {}

    /** @param allowed null if the check threw */
    private record Answer(Check check, Boolean allowed, Duration took) {}

    private static Answer ask(VelocityCounter member, Check check) {
        long start = System.nanoTime();
        Boolean allowed;
        try {
            allowed = member.allow(check.payer(), check.payment(), NOW);
        } catch (VelocityUnavailableException failed) {
            allowed = null;
        }
        return new Answer(check, allowed, Duration.ofNanos(System.nanoTime() - start));
    }

    /** 8 payers, each with 2 payments counted: 3 of any later payments fit. */
    private static List<String> payersWithTwoCounted(String prefix, VelocityCounter member) {
        List<String> payers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String payer = prefix + "-" + UUID.randomUUID();
            assertThat(member.allow(payer, UUID.randomUUID(), NOW)).isTrue();
            assertThat(member.allow(payer, UUID.randomUUID(), NOW)).isTrue();
            payers.add(payer);
        }
        return payers;
    }

    /** 40 checks: 5 distinct payments for each of the 8 payers, spread over the members. */
    private static List<Callable<Answer>> fortyChecks(List<String> payers, CountDownLatch go, VelocityCounter... on) {
        List<Callable<Answer>> checks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            for (String payer : payers) {
                VelocityCounter member = on[checks.size() % on.length];
                var check = new Check(payer, UUID.randomUUID());
                checks.add(() -> {
                    assertThat(go.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                    return ask(member, check);
                });
            }
        }
        return checks;
    }

    private static CountDownLatch firstMigration(HazelcastInstance member) {
        var latch = new CountDownLatch(1);
        member.getPartitionService().addMigrationListener(new MigrationListener() {
            @Override
            public void migrationStarted(MigrationState state) {
                latch.countDown();
            }

            @Override
            public void migrationFinished(MigrationState state) {}

            @Override
            public void replicaMigrationCompleted(ReplicaMigrationEvent event) {}

            @Override
            public void replicaMigrationFailed(ReplicaMigrationEvent event) {}
        });
        return latch;
    }

    @Test
    @DisplayName(
            "REQ-PF-032 a member joins under load: every check answers or throws in time, never more than 5 allowed,"
                    + " and a check that threw can be retried")
    void memberJoinsUnderLoad() throws Exception {
        HazelcastInstance memberA = startMember(0);
        VelocityCounter onA = new HazelcastVelocityCounter(memberA, LIMITS);
        List<String> duringMigration = payersWithTwoCounted("during", onA);
        List<String> afterStart = payersWithTwoCounted("after", onA);
        CountDownLatch migrating = firstMigration(memberA);
        List<Answer> answers = new ArrayList<>();
        ExecutorService starter = Executors.newSingleThreadExecutor();
        HazelcastInstance memberB;
        try {
            var joining = CompletableFuture.supplyAsync(() -> startMember(1), starter);
            // as soon as A starts to move its partitions
            answers.addAll(concurrently(fortyChecks(duringMigration, migrating, onA)));
            memberB = joining.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            starter.shutdown();
        }
        VelocityCounter onB = new HazelcastVelocityCounter(memberB, LIMITS);
        // as soon as B is usable, whatever is still moving
        answers.addAll(concurrently(fortyChecks(afterStart, new CountDownLatch(0), onA, onB)));

        // the bound, plus room for a slow machine
        assertThat(answers)
                .hasSize(80)
                .allSatisfy(answer ->
                        assertThat(answer.took()).isLessThan(HazelcastVelocityCounter.CALL_TIMEOUT.plusSeconds(5)));

        awaitClusterSafe(memberA, memberB);
        for (Answer answer : answers) {
            // a redelivered check, on the other member
            boolean again = onB.allow(answer.check().payer(), answer.check().payment(), NOW);
            if (answer.allowed() != null) {
                assertThat(again).as("an answered check keeps its answer").isEqualTo(answer.allowed());
            }
        }
        for (String payer :
                answers.stream().map(a -> a.check().payer()).distinct().toList()) {
            long allowed = answers.stream()
                    .filter(a -> a.check().payer().equals(payer))
                    .filter(a -> onA.allow(payer, a.check().payment(), NOW))
                    .count();
            assertThat(allowed).as("2 before the join + 3").isEqualTo(3);
            assertThat(onA.allow(payer, UUID.randomUUID(), NOW))
                    .as("a sixth payment")
                    .isFalse();
        }
    }
}
