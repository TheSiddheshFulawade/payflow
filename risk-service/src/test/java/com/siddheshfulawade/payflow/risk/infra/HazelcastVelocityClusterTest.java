package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.config.Config;
import com.hazelcast.config.ServerSocketEndpointConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.EndpointQualifier;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.spi.impl.SerializationServiceSupport;
import com.hazelcast.spi.properties.ClusterProperty;
import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Two real Hazelcast members in this JVM, joined over TCP on loopback, each with its own counter on its own member.
 * No Docker needed. The clock is passed in, so every check uses the fixed instant {@link #NOW} unless a test moves it.
 */
class HazelcastVelocityClusterTest {

    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final RiskLimits LIMITS = RiskLimits.defaults();

    private int[] ports;
    private HazelcastInstance memberA;
    private HazelcastInstance memberB;
    private VelocityCounter onA;
    private VelocityCounter onB;

    @BeforeEach
    void startTwoMembers() throws IOException {
        ports = freePorts();
        String cluster = "risk-test-" + UUID.randomUUID();
        List<String> members = List.of("127.0.0.1:" + ports[0], "127.0.0.1:" + ports[1]);
        memberA = Hazelcast.newHazelcastInstance(
                HazelcastConfigFactory.build(new HazelcastProperties(cluster, ports[0], members, "127.0.0.1"), LIMITS));
        memberB = Hazelcast.newHazelcastInstance(
                HazelcastConfigFactory.build(new HazelcastProperties(cluster, ports[1], members, "127.0.0.1"), LIMITS));
        Awaitility.await("two members joined")
                .atMost(WAIT)
                .until(() -> clusterSize(memberA) == 2 && clusterSize(memberB) == 2);
        // these tests are about a running cluster; HazelcastMemberTest covers the start
        Awaitility.await("both members know the owner of every partition")
                .atMost(WAIT)
                .until(() -> knowsEveryOwner(memberA) && knowsEveryOwner(memberB));
        awaitClusterSafe(memberA, memberB);
        onA = new HazelcastVelocityCounter(memberA, LIMITS);
        onB = new HazelcastVelocityCounter(memberB, LIMITS);
    }

    @AfterEach
    void stopMembers() {
        for (HazelcastInstance member : new HazelcastInstance[] {memberA, memberB}) {
            if (member != null) {
                member.getLifecycleService().terminate();
            }
        }
    }

    private static boolean knowsEveryOwner(HazelcastInstance member) {
        return member.getPartitionService().getPartitions().stream()
                .allMatch(partition -> partition.getOwner() != null);
    }

    static int clusterSize(HazelcastInstance member) {
        return member.getCluster().getMembers().size();
    }

    static int[] freePorts() throws IOException {
        try (ServerSocket first = new ServerSocket(0);
                ServerSocket second = new ServerSocket(0)) {
            return new int[] {first.getLocalPort(), second.getLocalPort()};
        }
    }

    private static String payer() {
        return "payer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    static void awaitClusterSafe(HazelcastInstance... members) {
        Awaitility.await("cluster safe (all backups in place)").atMost(WAIT).until(() -> {
            for (HazelcastInstance member : members) {
                if (!member.getPartitionService().isClusterSafe()) {
                    return false;
                }
            }
            return true;
        });
    }

    /** A payer name whose velocity entry lives on {@code owner}, so killing the owner really tests the backup. */
    private String payerOwnedBy(HazelcastInstance owner) {
        UUID ownerId = owner.getCluster().getLocalMember().getUuid();
        for (int i = 0; i < 1_000; i++) {
            String candidate = "payer-" + i + "-" + UUID.randomUUID().toString().substring(0, 4);
            var partitionOwner =
                    memberA.getPartitionService().getPartition(candidate).getOwner();
            if (partitionOwner != null && partitionOwner.getUuid().equals(ownerId)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no payer name found for the member");
    }

    // ---- REQ-PF-032: both members see one count ----

    @Test
    @DisplayName("REQ-PF-032 five payments alternating between two members are allowed, the sixth is refused on both")
    void bothMembersSeeOneCount() {
        String payer = payer();

        for (int i = 0; i < 5; i++) {
            VelocityCounter member = i % 2 == 0 ? onA : onB;
            assertThat(member.allow(payer, id(), NOW)).as("payment %d", i + 1).isTrue();
        }

        assertThat(onA.allow(payer, id(), NOW)).as("6th on member A").isFalse();
        assertThat(onB.allow(payer, id(), NOW)).as("6th on member B").isFalse();
    }

    @Test
    @DisplayName("REQ-PF-032 the same payment checked again and again on both members counts once")
    void redeliveredCheckCountsOnce() {
        String payer = payer();
        for (int i = 0; i < 3; i++) {
            assertThat(onA.allow(payer, id(), NOW)).isTrue();
        }
        UUID redelivered = id();

        for (int i = 0; i < 10; i++) {
            assertThat((i % 2 == 0 ? onA : onB).allow(payer, redelivered, NOW))
                    .as("check %d of the same payment", i + 1)
                    .isTrue();
        }

        assertThat(onB.allow(payer, id(), NOW))
                .as("the 5th distinct payment still fits")
                .isTrue();
        assertThat(onA.allow(payer, id(), NOW)).as("the 6th does not").isFalse();
    }

    @Test
    @DisplayName("REQ-PF-031 an entry exactly 60 s old no longer counts, one 59.999 s old still does")
    void windowBoundaryThroughHazelcast() {
        String payer = payer();
        for (int i = 0; i < 5; i++) {
            assertThat(onA.allow(payer, id(), NOW)).isTrue();
        }

        assertThat(onB.allow(payer, id(), NOW.plusMillis(59_999)))
                .as("59.999 s later")
                .isFalse();
        assertThat(onB.allow(payer, id(), NOW.plusSeconds(60))).as("60 s later").isTrue();
    }

    @Test
    @DisplayName("REQ-PF-031 payers are counted independently")
    void payersAreIndependent() {
        String busy = payer();
        String quiet = payer();
        for (int i = 0; i < 5; i++) {
            assertThat(onA.allow(busy, id(), NOW)).isTrue();
        }

        assertThat(onA.allow(busy, id(), NOW)).isFalse();
        for (int i = 0; i < 5; i++) {
            assertThat(onB.allow(quiet, id(), NOW))
                    .as("quiet payer, payment %d", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("REQ-PF-032 40 threads on two members, distinct payments of one payer: exactly 5 are allowed")
    void concurrentChecksAllowExactlyTheLimit() throws Exception {
        String payer = payer();
        List<Callable<Boolean>> checks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            VelocityCounter member = i % 2 == 0 ? onA : onB;
            UUID payment = id();
            checks.add(() -> member.allow(payer, payment, NOW));
        }

        List<Boolean> results = concurrently(checks);

        assertThat(results.stream().filter(allowed -> allowed).count()).isEqualTo(5);
    }

    /** Keeps the owner busy. It runs on the calling member and has no backup, so it is never serialized. */
    private record Blocker(CountDownLatch running, CountDownLatch release)
            implements EntryProcessor<String, VelocityWindow, Boolean> {

        @Override
        public Boolean process(Map.Entry<String, VelocityWindow> entry) {
            running.countDown();
            try {
                return release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public EntryProcessor<String, VelocityWindow, Boolean> getBackupProcessor() {
            return null;
        }
    }

    @Test
    @DisplayName("REQ-PF-032 a check that timed out on a busy owner can be retried on either member and counts once")
    void timedOutCheckIsRetried() throws Exception {
        String payer = payerOwnedBy(memberA);
        for (int i = 0; i < 4; i++) {
            assertThat(onA.allow(payer, id(), NOW)).isTrue();
        }
        UUID fifth = id();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            memberA.<String, VelocityWindow>getMap(HazelcastVelocityCounter.MAP_NAME)
                    .submitToKey(payer, new Blocker(running, release));
            assertThat(running.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> onA.allow(payer, fifth, NOW))
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
        }

        assertThat(onB.allow(payer, fifth, NOW)).as("retry on B").isTrue();
        assertThat(onA.allow(payer, fifth, NOW)).as("retry on A").isTrue();
        assertThat(onA.allow(payer, id(), NOW)).as("a sixth payment").isFalse();
    }

    // ---- REQ-PF-032: a member dies, the count survives (C3) ----

    enum Death {
        /** member.shutdown(): partitions are migrated first */
        GRACEFUL,
        /** terminate(): the member vanishes; the survivor must promote its backup */
        HARD
    }

    @ParameterizedTest
    @EnumSource(Death.class)
    @DisplayName("REQ-PF-032 after the member that owns the count dies, the survivor still counts 3 payments")
    void survivorKeepsTheCount(Death death) {
        String payer = payerOwnedBy(memberB);
        List<UUID> first = List.of(id(), id(), id());
        for (int i = 0; i < first.size(); i++) {
            assertThat((i % 2 == 0 ? onA : onB).allow(payer, first.get(i), NOW)).isTrue();
        }
        awaitClusterSafe(memberA, memberB);

        die(memberB, death);

        assertThat(onA.allow(payer, first.get(0), NOW))
                .as("a redelivered payment is still free")
                .isTrue();
        assertThat(onA.allow(payer, id(), NOW)).as("4th payment").isTrue();
        assertThat(onA.allow(payer, id(), NOW)).as("5th payment").isTrue();
        assertThat(onA.allow(payer, id(), NOW))
                .as("6th payment: the count of 3 was kept")
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(Death.class)
    @DisplayName("REQ-PF-032 payments allowed concurrently before a member dies are all remembered after it")
    void backupStaysConsistentUnderLoad(Death death) throws Exception {
        String payer = payerOwnedBy(memberB);
        List<UUID> payments = new ArrayList<>();
        List<Callable<Boolean>> checks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            VelocityCounter member = i % 2 == 0 ? onA : onB;
            UUID payment = id();
            payments.add(payment);
            checks.add(() -> member.allow(payer, payment, NOW));
        }
        List<Boolean> results = concurrently(checks);
        List<UUID> allowed = new ArrayList<>();
        for (int i = 0; i < payments.size(); i++) {
            if (results.get(i)) {
                allowed.add(payments.get(i));
            }
        }
        assertThat(allowed).as("allowed before the death").hasSize(5);
        awaitClusterSafe(memberA, memberB);

        die(memberB, death);

        for (UUID payment : allowed) {
            assertThat(onA.allow(payer, payment, NOW))
                    .as("allowed payment is remembered")
                    .isTrue();
        }
        assertThat(onA.allow(payer, id(), NOW))
                .as("exactly 5 are counted: no sixth")
                .isFalse();
        for (UUID payment : payments) {
            if (!allowed.contains(payment)) {
                assertThat(onA.allow(payer, payment, NOW))
                        .as("a refused payment was never stored")
                        .isFalse();
            }
        }
    }

    private void die(HazelcastInstance member, Death death) {
        if (death == Death.GRACEFUL) {
            member.shutdown();
        } else {
            member.getLifecycleService().terminate();
        }
        Awaitility.await("survivor is alone").atMost(WAIT).until(() -> clusterSize(memberA) == 1);
    }

    static <T> List<T> concurrently(List<? extends Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all threads started")
                    .isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- configuration of the members ----

    /**
     * The member port speaks the member protocol only (advanced network with a single MEMBER endpoint): no client,
     * REST or memcache endpoint, so nothing can read or write the velocity map through it. Joining is configured on the
     * advanced network config, because the plain network config is ignored once that is enabled.
     */
    private static void assertMemberOnlyNetwork(Config config, int port, String bindAddress, List<String> members) {
        var advanced = config.getAdvancedNetworkConfig();
        assertThat(advanced.isEnabled()).as("advanced network").isTrue();
        assertThat(advanced.getEndpointConfigs())
                .as("exactly one endpoint, the member one: no CLIENT, REST or MEMCACHE")
                .containsOnlyKeys(EndpointQualifier.MEMBER);
        var member = (ServerSocketEndpointConfig) advanced.getEndpointConfigs().get(EndpointQualifier.MEMBER);
        assertThat(member.getPort()).as("member port").isEqualTo(port);
        assertThat(member.isPortAutoIncrement())
                .as("a taken port fails the start")
                .isFalse();
        assertThat(member.getInterfaces().isEnabled()).as("interfaces").isTrue();
        assertThat(member.getInterfaces().getInterfaces()).containsExactly(bindAddress);

        var join = advanced.getJoin();
        assertThat(join.getMulticastConfig().isEnabled()).as("multicast").isFalse();
        assertThat(join.getAutoDetectionConfig().isEnabled())
                .as("auto-detection")
                .isFalse();
        assertThat(join.getTcpIpConfig().isEnabled()).as("tcp-ip").isTrue();
        assertThat(join.getTcpIpConfig().getMembers()).containsExactlyElementsOf(members);
    }

    private static void assertLockedDownProperties(Config config) {
        assertThat(config.getProperty("hazelcast.socket.bind.any"))
                .as("bind.any")
                .isEqualTo("false");
        var effective = new com.hazelcast.spi.properties.HazelcastProperties(config);
        assertThat(effective.getBoolean(ClusterProperty.SOCKET_BIND_ANY))
                .as("effective bind any")
                .isFalse();
        assertThat(effective.getBoolean(ClusterProperty.SOCKET_SERVER_BIND_ANY))
                .as("effective server bind any (the property that counts with advanced network)")
                .isFalse();
        assertThat(config.getProperty("hazelcast.phone.home.enabled"))
                .as("phone home")
                .isEqualTo("false");
        assertThat(config.getProperty("hazelcast.shutdownhook.enabled"))
                .as("shutdown hook")
                .isEqualTo("false");
        assertThat(config.getProperty("hazelcast.operation.fail.on.indeterminate.state"))
                .as("an operation whose outcome is unknown fails instead of being guessed")
                .isEqualTo("true");
        assertThat(config.getJetConfig().isEnabled()).as("jet").isFalse();
        var management = config.getManagementCenterConfig();
        assertThat(management.isScriptingEnabled())
                .as("management center scripting")
                .isFalse();
        assertThat(management.isConsoleEnabled())
                .as("management center console")
                .isFalse();
        assertThat(management.isDataAccessEnabled())
                .as("management center data access")
                .isFalse();
    }

    @Test
    @DisplayName("REQ-PF-032 a running member listens for members only, on loopback, joined by TCP-IP, nothing else on")
    void membersAreLockedDown() {
        var config = memberA.getConfig();

        assertMemberOnlyNetwork(
                config, ports[0], "127.0.0.1", List.of("127.0.0.1:" + ports[0], "127.0.0.1:" + ports[1]));
        assertLockedDownProperties(config);
        assertThat(memberA.getCluster().getLocalMember().getAddress().getHost()).isEqualTo("127.0.0.1");
        assertThat(memberA.getCluster().getLocalMember().getAddress().getPort()).isEqualTo(ports[0]);
    }

    @Test
    @DisplayName("REQ-PF-032 a client-protocol connection to the member port is closed without a client handshake")
    void clientProtocolIsRefusedOnTheMemberPort() throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ports[0]), 5_000);
            socket.setSoTimeout(250);
            socket.getOutputStream().write("CP2".getBytes(StandardCharsets.US_ASCII)); // the client protocol header
            socket.getOutputStream().flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();

            Awaitility.await("member closes a client-protocol connection")
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> readToEnd(socket.getInputStream(), received));

            assertThat(received.toString(StandardCharsets.US_ASCII))
                    .as("at most the unexpected-protocol marker, never a client handshake")
                    .isIn("", "HZX");
        }
    }

    /** True when the other side closed the connection; false when it is still open and silent. */
    private static boolean readToEnd(InputStream in, ByteArrayOutputStream into) throws IOException {
        try {
            int b;
            while ((b = in.read()) != -1) {
                into.write(b);
            }
            return true;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (SocketException reset) {
            return true;
        }
    }

    @Test
    @DisplayName("REQ-PF-032 the velocity map keeps one synchronous backup and expires entries after 2 windows")
    void velocityMapHasSynchronousBackupAndTtl() {
        var map = memberA.getConfig().getMapConfig(HazelcastVelocityCounter.MAP_NAME);

        assertThat(HazelcastVelocityCounter.MAP_NAME).isEqualTo("velocity");
        assertThat(map.getBackupCount()).as("sync backups").isEqualTo(1);
        assertThat(map.getAsyncBackupCount()).as("async backups").isZero();
        assertThat(map.getTimeToLiveSeconds()).isEqualTo(120);
    }

    @Test
    @DisplayName("REQ-PF-032 the entry TTL follows the configured window (never shorter than it)")
    void ttlFollowsTheWindow() {
        var limits = new RiskLimits(1, 1, 1, Duration.ofSeconds(10));

        var config = HazelcastConfigFactory.build(
                new HazelcastProperties("c", 5701, List.of("127.0.0.1:5701"), "127.0.0.1"), limits);

        assertThat(config.getMapConfig(HazelcastVelocityCounter.MAP_NAME).getTimeToLiveSeconds())
                .isEqualTo(20);
    }

    @Test
    @DisplayName("REQ-PF-032 properties reach the config: cluster name, port, members, bind address")
    void propertiesReachTheConfig() {
        var props = new HazelcastProperties("my-cluster", 5999, List.of("10.0.0.1:5999", "10.0.0.2:5999"), "10.0.0.1");

        var config = HazelcastConfigFactory.build(props, LIMITS);

        assertThat(config.getClusterName()).isEqualTo("my-cluster");
        assertMemberOnlyNetwork(config, 5999, "10.0.0.1", List.of("10.0.0.1:5999", "10.0.0.2:5999"));
        assertLockedDownProperties(config);
    }

    /** Outside every PayFlow package, Serializable, and not one of Hazelcast's built-in types. */
    @Test
    @DisplayName("REQ-PF-032 Java deserialization of a class outside PayFlow is refused by the members")
    void foreignClassesCannotBeDeserialised() {
        SerializationService serialization = ((SerializationServiceSupport) memberA).getSerializationService();
        Data foreign = serialization.toData(new org.springframework.util.LinkedMultiValueMap<String, String>());

        assertThatThrownBy(() -> serialization.toObject(foreign))
                .as("the filter blocks classes that are not whitelisted")
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("REQ-PF-032 the filter is not too tight: a non-empty VelocityWindow round-trips through the member")
    void payflowClassesStillDeserialise() {
        SerializationService serialization = ((SerializationServiceSupport) memberA).getSerializationService();
        VelocityWindow window = VelocityWindow.empty()
                .add(id(), NOW.toEpochMilli(), 5, 60_000)
                .window()
                .add(id(), NOW.toEpochMilli() + 1, 5, 60_000)
                .window();
        assertThat(window.size()).as("a real window with entries").isEqualTo(2);

        Data data = serialization.toData(window);

        assertThat(serialization.<VelocityWindow>toObject(data)).isEqualTo(window);
    }

    @Test
    @DisplayName("REQ-PF-032 the filter is not too tight: a VelocityEntryProcessor round-trips through the member")
    void entryProcessorStillDeserialises() {
        SerializationService serialization = ((SerializationServiceSupport) memberA).getSerializationService();
        var processor = new VelocityEntryProcessor(id(), NOW.toEpochMilli(), 5, 60_000);

        Data data = serialization.toData(processor);

        assertThat(serialization.<VelocityEntryProcessor>toObject(data)).isEqualTo(processor);
    }
}
