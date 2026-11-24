package com.siddheshfulawade.payflow.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The shipped defaults of application.yml, resolved the way Boot resolves them, without starting anything. */
class RiskConfigTest {

    private static List<PropertySource<?>> yaml() throws IOException {
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    }

    /** application.yml alone (no real environment variables), plus the given overrides in front of it. */
    private static ConfigurableEnvironment environment(Map<String, Object> overrides) throws IOException {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        yaml().forEach(env.getPropertySources()::addLast);
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return env;
    }

    @Test
    @DisplayName("REQ-PF-004 the service binds to loopback by default")
    void bindsToLoopbackByDefault() throws IOException {
        assertThat(environment(Map.of()).getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("REQ-PF-004 RISK_BIND overrides the bind address")
    void bindAddressIsOverridable() throws IOException {
        assertThat(environment(Map.of("RISK_BIND", "0.0.0.0")).getProperty("server.address"))
                .isEqualTo("0.0.0.0");
    }

    @Test
    @DisplayName("REQ-PF-004 the default JDBC url keeps server error details out of exceptions")
    void jdbcUrlHidesServerErrorDetail() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.datasource.url"))
                .startsWith("jdbc:postgresql://")
                .contains("logServerErrorDetail=false");
    }

    @Test
    @DisplayName(
            "REQ-PF-031 every pooled connection starts with a lock_timeout of 5 s, so a stuck row lock ends in an error")
    void connectionsSetLockTimeout() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.datasource.hikari.connection-init-sql"))
                .containsIgnoringCase("lock_timeout")
                .contains("5s");
    }

    @Test
    @DisplayName("REQ-PF-004 the database password has no default: it must come from RISK_DB_PASSWORD")
    void passwordHasNoDefault() throws IOException {
        Object raw = yaml().get(0).getProperty("spring.datasource.password");

        assertThat(raw).isEqualTo("${RISK_DB_PASSWORD}");
    }

    @Test
    @DisplayName("REQ-PF-004 form-content filter is off, so no request body is read before limits are applied")
    void formContentFilterIsOff() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.mvc.formcontent.filter.enabled"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("REQ-PF-001 Flyway reads the shared messaging scripts and this service's own")
    void flywayLocations() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.flyway.locations"))
                .contains("classpath:db/messaging")
                .contains("classpath:db/migration");
    }

    @Test
    @DisplayName("REQ-PF-032 the Hazelcast member binds to loopback by default and has a cluster name")
    void hazelcastDefaults() throws IOException {
        ConfigurableEnvironment env = environment(Map.of());

        assertThat(env.getProperty("payflow.risk.hazelcast.bind-address")).isEqualTo("127.0.0.1");
        assertThat(env.getProperty("payflow.risk.hazelcast.cluster-name")).isNotBlank();
        assertThat(env.getProperty("payflow.risk.hazelcast.port")).isNotBlank();
    }
}
