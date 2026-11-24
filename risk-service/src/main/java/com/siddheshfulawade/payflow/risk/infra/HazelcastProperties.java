package com.siddheshfulawade.payflow.risk.infra;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This instance's Hazelcast member. A wrong setting stops the start: the member would count alone, or listen where it
 * should not.
 *
 * @param clusterName members with another name never join
 * @param port the member listens on exactly this port
 * @param members {@code host:port} of every member, this one included
 * @param bindAddress local address to listen on, or a pattern such as {@code 172.20.0.*} in a container network
 */
@ConfigurationProperties("payflow.risk.hazelcast")
record HazelcastProperties(String clusterName, int port, List<String> members, String bindAddress) {

    HazelcastProperties {
        requireText(clusterName, "cluster-name");
        requireText(bindAddress, "bind-address");
        require(port >= 1 && port <= 65_535, "port must be 1 to 65535");
        require(members != null && !members.isEmpty(), "members must not be empty");
        // a member that is missing from its own list is a typo in the port or in the list
        String ownPort = ":" + port;
        require(members.stream().anyMatch(m -> m.endsWith(ownPort)), "members must contain this member's own port");
        members = List.copyOf(members);
    }

    private static void requireText(String value, String property) {
        require(value != null && !value.isBlank(), property + " must not be blank");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException("payflow.risk.hazelcast: " + message);
        }
    }
}
