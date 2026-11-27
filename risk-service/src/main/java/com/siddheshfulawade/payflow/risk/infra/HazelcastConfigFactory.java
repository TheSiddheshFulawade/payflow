package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.config.AdvancedNetworkConfig;
import com.hazelcast.config.ClassFilter;
import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.ServerSocketEndpointConfig;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * The embedded member: its port speaks the member protocol only, and every count has one synchronous backup.
 *
 * <p>The port has no authentication (TLS and member authentication are Enterprise features), and the member list is for
 * discovery, not an access list: whoever reaches the port can join. The control is the network: loopback, or a network
 * that only the members are on.
 *
 * <p>Split-brain: while a network partition separates the members, each side counts on its own, so a payer may get up
 * to twice the velocity limit; when the sides merge, one side's window of a payer wins. Accepted: velocity is a risk
 * control, not a money invariant. The daily total and the decision live in PostgreSQL and stay exact.
 */
final class HazelcastConfigFactory {

    /** Longer than the caller's own bound ({@link HazelcastVelocityCounter#CALL_TIMEOUT}), which ends the wait first. */
    private static final Duration OPERATION_CALL_TIMEOUT = Duration.ofSeconds(5);

    /** Hazelcast's errors for the deserialization allow-list (see {@link #serializationFilter()}). */
    static final String ALLOWED_ERRORS = "/hazelcast-allowed-errors.txt";

    static Config build(HazelcastProperties properties, RiskLimits limits) {
        Config config = new Config();
        config.setClusterName(properties.clusterName());
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        // Hazelcast itself gives up on a call whose owner is gone or hung, instead of after the default 60 s. The
        // caller stopped waiting long before (HazelcastVelocityCounter); this frees what the call still holds inside.
        config.setProperty(
                "hazelcast.operation.call.timeout.millis", String.valueOf(OPERATION_CALL_TIMEOUT.toMillis()));
        // A hung member is evicted after 10 s without a heartbeat (default 60 s) and its backups take over: well inside
        // the risk step's 65 s of re-sends (5 + 10 + 20 + 30 s), so a check survives a hung member.
        config.setProperty("hazelcast.heartbeat.interval.seconds", "1");
        config.setProperty("hazelcast.max.no.heartbeat.seconds", "10");
        // A missing backup ack is an error (the default would answer anyway). The check's outcome is then unknown; the
        // re-sent check is idempotent, because it asks for the same payment id.
        config.setProperty("hazelcast.operation.fail.on.indeterminate.state", "true");
        // Spring shuts the member down (RiskConfig). Hazelcast's own JVM hook would terminate it at the same time,
        // under running checks and without handing over its partitions.
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.setProperty("hazelcast.graceful.shutdown.max.wait", "30");
        config.getJetConfig().setEnabled(false);
        // data access is on by default; the other two are written out so that no default can open them
        config.getManagementCenterConfig()
                .setScriptingEnabled(false)
                .setConsoleEnabled(false)
                .setDataAccessEnabled(false);
        network(config, properties);
        config.getSerializationConfig().setJavaSerializationFilterConfig(serializationFilter());
        config.addMapConfig(velocityMap(limits));
        return config;
    }

    private static void network(Config config, HazelcastProperties properties) {
        // the default (true) listens on 0.0.0.0 whatever the interface below says
        config.setProperty("hazelcast.socket.bind.any", "false");
        // a taken port must fail the start: on the next port the others would not find this member
        ServerSocketEndpointConfig member =
                new ServerSocketEndpointConfig().setPort(properties.port()).setPortAutoIncrement(false);
        member.getInterfaces().setEnabled(true).addInterface(properties.bindAddress());
        // the member endpoint and no other: the plain network config would also serve clients on this port
        AdvancedNetworkConfig network =
                config.getAdvancedNetworkConfig().setEnabled(true).setMemberEndpointConfig(member);
        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).setMembers(properties.members());
    }

    private static MapConfig velocityMap(RiskLimits limits) {
        // Twice the window: an entry outlives its newest payment even if the members' clocks differ a little.
        // Rounded up, because 0 would mean "never expires".
        long ttlSeconds = Math.ceilDiv(2 * limits.window().toMillis(), 1_000);
        return new MapConfig(HazelcastVelocityCounter.MAP_NAME)
                // Synchronous: an allowed payment is on owner and backup before the check is answered, while a backup
                // exists. A lone member has none, and after a member change the new one takes a moment to copy.
                .setBackupCount(1)
                .setAsyncBackupCount(0)
                .setTimeToLiveSeconds(Math.toIntExact(ttlSeconds));
    }

    /**
     * Java deserialization creates exactly these classes, so no packages and no prefixes. Hazelcast's defaults would
     * allow all of {@code java.*}, and a prefix such as {@code com.hazelcast.} would open every Serializable class in
     * the jar, each one a possible gadget for a crafted stream from whoever reaches the member port.
     *
     * <p>Every Hazelcast error is listed, not only the ones the velocity check expects: an error response that can't be
     * read leaves its operation unanswered, and that includes Hazelcast's internal ones. A join's partition migration
     * that got {@code PartitionStateVersionMismatchException} back never finished, and the cluster stayed unsafe. The
     * names come from {@value #ALLOWED_ERRORS}, generated from the jar scan in {@code HazelcastSerializationFilterTest},
     * which fails on a Hazelcast upgrade until the file is regenerated.
     */
    private static JavaSerializationFilterConfig serializationFilter() {
        ClassFilter allowed = new ClassFilter()
                .addClasses(VelocityWindow.class.getName(), VelocityEntryProcessor.class.getName())
                // the fields of those two (CollSer is how Map.copyOf is serialized)
                .addClasses("java.util.CollSer", "java.util.UUID", "java.lang.Long", "java.lang.Number")
                // Hazelcast's own errors and their base classes: with these, a "partition is migrating, try again" is
                // retried at once, and a member's internal operations get their error answers. An error that still
                // can't be read (say, a JDK cause that is not listed) fails closed for a check: it waits until the
                // caller's bound (1 s), throws, and the sender re-sends the command.
                .addClasses(allowedErrors(ALLOWED_ERRORS).toArray(String[]::new))
                // what those errors write besides strings and other errors: Throwable's stack trace and its empty
                // suppressed list, and the member version that MemberLeftException writes (its address and UUID go as
                // plain strings and longs). Its readObject rebuilds the Address from the host string, which resolves
                // it: members are configured by IP, so no DNS lookup; a host name would cost one lookup per error read.
                .addClasses(
                        "java.lang.StackTraceElement",
                        "[Ljava.lang.StackTraceElement;",
                        "java.util.Collections$EmptyList",
                        "com.hazelcast.version.MemberVersion");
        return new JavaSerializationFilterConfig().setDefaultsDisabled(true).setWhitelist(allowed);
    }

    /**
     * The class names in a classpath resource: one per line, {@code #} starts a comment line. Fails closed: a missing or
     * empty list stops the member start instead of starting a member that can't read its peers' errors.
     */
    static List<String> allowedErrors(String resource) {
        InputStream in = HazelcastConfigFactory.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Hazelcast error allow-list " + resource + " is not on the classpath");
        }
        List<String> names;
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            names = reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Hazelcast error allow-list " + resource + " can't be read", e);
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("Hazelcast error allow-list " + resource + " is empty");
        }
        return names;
    }

    private HazelcastConfigFactory() {}
}
