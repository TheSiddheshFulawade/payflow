package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * A member that starts with a wrong setting would either not find the others (a split cluster that counts twice) or
 * listen where it should not. So the service refuses to start, and the message names the setting.
 */
class HazelcastPropertiesTest {

    private static final List<String> MEMBERS = List.of("127.0.0.1:5701", "127.0.0.1:5702");

    /** The refusal names the property, in kebab-case or camelCase. */
    private static void assertRefusedNaming(Supplier<?> create, String property) {
        assertThatIllegalArgumentException()
                .isThrownBy(create::get)
                .satisfies(e -> assertThat(e.getMessage().toLowerCase().replace("-", ""))
                        .as("message names '%s'", property)
                        .contains(property.toLowerCase().replace("-", "")));
    }

    @Test
    @DisplayName("REQ-PF-032 a valid configuration is accepted, a pattern as bind address too")
    void validConfigurationsPass() {
        assertThat(new HazelcastProperties("c", 5701, MEMBERS, "127.0.0.1").port())
                .isEqualTo(5701);
        assertThat(new HazelcastProperties("c", 5702, MEMBERS, "172.20.0.*").bindAddress())
                .isEqualTo("172.20.0.*");
        assertThat(new HazelcastProperties("c", 1, List.of("host:1"), "127.0.0.1").port())
                .isEqualTo(1);
        assertThat(new HazelcastProperties("c", 65535, List.of("host:65535"), "127.0.0.1").port())
                .isEqualTo(65535);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("REQ-PF-032 a blank cluster name is refused: members would join the wrong cluster or none")
    void blankClusterName(String name) {
        assertRefusedNaming(() -> new HazelcastProperties(name, 5701, MEMBERS, "127.0.0.1"), "cluster-name");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("REQ-PF-032 a blank bind address is refused: it would listen on every interface")
    void blankBindAddress(String address) {
        assertRefusedNaming(() -> new HazelcastProperties("c", 5701, MEMBERS, address), "bind-address");
    }

    @Test
    @DisplayName("REQ-PF-032 no members is refused: the member would start alone and count alone")
    void noMembers() {
        assertRefusedNaming(() -> new HazelcastProperties("c", 5701, null, "127.0.0.1"), "members");
        assertRefusedNaming(() -> new HazelcastProperties("c", 5701, List.of(), "127.0.0.1"), "members");
    }

    @Test
    @DisplayName("REQ-PF-032 a member list that does not contain this member's own port is refused")
    void membersWithoutOwnPort() {
        assertRefusedNaming(
                () -> new HazelcastProperties("c", 5701, List.of("127.0.0.1:5702", "127.0.0.1:5703"), "127.0.0.1"),
                "members");
    }

    @Test
    @DisplayName("REQ-PF-032 'ends with the port' means ':5701', not '15701' or a host name")
    void ownPortMustBeAWholePort() {
        assertRefusedNaming(
                () -> new HazelcastProperties("c", 5701, List.of("127.0.0.1:15701", "127.0.0.1:5702"), "127.0.0.1"),
                "members");
        assertRefusedNaming(() -> new HazelcastProperties("c", 5701, List.of("host5701"), "127.0.0.1"), "members");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536, 70000, Integer.MIN_VALUE})
    @DisplayName("REQ-PF-032 a port outside 1..65535 is refused")
    void portOutOfRange(int port) {
        assertRefusedNaming(
                () -> new HazelcastProperties("c", port, List.of("127.0.0.1:" + port), "127.0.0.1"), "port");
    }

    @Test
    @DisplayName("REQ-PF-032 binding refuses the same mistakes at startup, and accepts a complete configuration")
    void bindingEnforcesTheSameRules() {
        Map<String, String> complete = Map.of(
                "payflow.risk.hazelcast.cluster-name", "payflow-risk",
                "payflow.risk.hazelcast.port", "5701",
                "payflow.risk.hazelcast.members[0]", "127.0.0.1:5701",
                "payflow.risk.hazelcast.members[1]", "127.0.0.1:5702",
                "payflow.risk.hazelcast.bind-address", "127.0.0.1");
        assertThat(bind(complete).members()).isEqualTo(MEMBERS);

        Map<String, String> noOwnPort = new java.util.HashMap<>(complete);
        noOwnPort.put("payflow.risk.hazelcast.port", "5799");
        assertThatThrownBy(() -> bind(noOwnPort)).hasStackTraceContaining("members");

        List<String> missing = new ArrayList<>(complete.keySet());
        for (String key : missing) {
            if (key.contains("members[1]")) {
                continue; // the second member is optional
            }
            Map<String, String> without = new java.util.HashMap<>(complete);
            without.remove(key);
            assertThatThrownBy(() -> bind(without)).as("without %s", key).isInstanceOf(RuntimeException.class);
        }
    }

    private static HazelcastProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("payflow.risk.hazelcast", HazelcastProperties.class)
                .get();
    }
}
