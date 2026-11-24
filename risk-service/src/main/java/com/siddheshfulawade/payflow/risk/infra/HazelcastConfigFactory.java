package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.config.ClassFilter;
import com.hazelcast.config.Config;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;

/**
 * The embedded member: closed to everything but the configured members, one synchronous backup of every count.
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
        // Spring shuts the member down (RiskConfig). Hazelcast's own JVM hook would terminate it at the same time,
        // under running checks and without handing over its partitions.
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        config.setProperty("hazelcast.graceful.shutdown.max.wait", "30");
        config.getJetConfig().setEnabled(false);
        network(config, properties);
        config.getSerializationConfig().setJavaSerializationFilterConfig(serializationFilter());
        config.addMapConfig(velocityMap(limits));
        return config;
    }

    private static void network(Config config, HazelcastProperties properties) {
        // the default (true) listens on 0.0.0.0 whatever the interface below says
        config.setProperty("hazelcast.socket.bind.any", "false");
        NetworkConfig network = config.getNetworkConfig();
        // a taken port must fail the start: on the next port the others would not find this member
        network.setPort(properties.port()).setPortAutoIncrement(false);
        network.getInterfaces().setEnabled(true).addInterface(properties.bindAddress());
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
                // synchronous: a counted payment is on both members before the check is answered
                .setBackupCount(1)
                .setAsyncBackupCount(0)
                .setTimeToLiveSeconds(Math.toIntExact(ttlSeconds));
    }

    /** Java deserialization creates only these classes. Hazelcast's defaults would allow all of {@code java.*}. */
    private static JavaSerializationFilterConfig serializationFilter() {
        ClassFilter allowed = new ClassFilter()
                .addPrefixes("com.siddheshfulawade.payflow.")
                // the fields of VelocityWindow and VelocityEntryProcessor (CollSer is how Map.copyOf is serialized)
                .addClasses("java.util.CollSer", "java.util.UUID", "java.lang.Long", "java.lang.Number")
                // Hazelcast's own errors travel between members as Java objects. Without them a "partition is
                // migrating, try again" can't be read, and the check hangs until its timeout instead of being retried.
                .addPackages("com.hazelcast.core", "com.hazelcast.spi.exception")
                .addClasses(
                        "java.lang.Throwable",
                        "java.lang.Exception",
                        "java.lang.RuntimeException",
                        "java.lang.IllegalStateException",
                        "java.io.IOException",
                        "java.lang.StackTraceElement",
                        "[Ljava.lang.StackTraceElement;",
                        "java.util.Collections$EmptyList");
        return new JavaSerializationFilterConfig().setDefaultsDisabled(true).setWhitelist(allowed);
    }

    private HazelcastConfigFactory() {}
}
