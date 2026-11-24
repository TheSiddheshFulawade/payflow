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

    static Config build(HazelcastProperties properties, RiskLimits limits) {
        Config config = new Config();
        config.setClusterName(properties.clusterName());
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        // Gives up on a call whose owner is gone or hung. The default of 60 s would hold the database transaction and
        // the Kafka partition too long.
        config.setProperty(
                "hazelcast.operation.call.timeout.millis",
                String.valueOf(HazelcastVelocityCounter.CALL_TIMEOUT.toMillis()));
        // A missing backup ack is an error (the default would answer anyway). The check's outcome is then unknown; the
        // retry is idempotent, because it asks for the same payment id.
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
     * allow all of {@code java.*}.
     */
    private static JavaSerializationFilterConfig serializationFilter() {
        ClassFilter allowed = new ClassFilter()
                .addClasses(VelocityWindow.class.getName(), VelocityEntryProcessor.class.getName())
                // the fields of those two (CollSer is how Map.copyOf is serialized)
                .addClasses("java.util.CollSer", "java.util.UUID", "java.lang.Long", "java.lang.Number")
                // Hazelcast's own errors travel between members as Java objects: with these, a "partition is
                // migrating, try again" is retried at once. An error that is missing here can't be read, and the check
                // fails closed: it waits until its timeout (5 s), throws, and the command is retried.
                .addClasses(
                        "com.hazelcast.core.HazelcastException",
                        "com.hazelcast.core.HazelcastInstanceNotActiveException",
                        "com.hazelcast.spi.exception.RetryableHazelcastException",
                        "com.hazelcast.spi.exception.PartitionMigratingException",
                        "com.hazelcast.spi.exception.WrongTargetException",
                        "com.hazelcast.spi.exception.CallerNotMemberException",
                        "com.hazelcast.spi.exception.TargetNotMemberException")
                // the base classes and fields of those errors
                .addClasses(
                        "java.lang.Throwable",
                        "java.lang.Exception",
                        "java.lang.RuntimeException",
                        "java.lang.IllegalStateException",
                        "java.lang.StackTraceElement",
                        "[Ljava.lang.StackTraceElement;",
                        "java.util.Collections$EmptyList");
        return new JavaSerializationFilterConfig().setDefaultsDisabled(true).setWhitelist(allowed);
    }

    private HazelcastConfigFactory() {}
}
