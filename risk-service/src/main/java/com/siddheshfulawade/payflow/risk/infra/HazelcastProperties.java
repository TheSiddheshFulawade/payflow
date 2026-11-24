package com.siddheshfulawade.payflow.risk.infra;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This instance's Hazelcast member.
 *
 * @param clusterName members with another name never join
 * @param port the member listens on exactly this port
 * @param members {@code host:port} of every member, this one included
 * @param bindAddress local address to listen on, or a pattern such as {@code 172.20.0.*} in a container network
 */
@ConfigurationProperties("payflow.risk.hazelcast")
record HazelcastProperties(String clusterName, int port, List<String> members, String bindAddress) {}
