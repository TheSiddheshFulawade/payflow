package com.siddheshfulawade.payflow.wallet;

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
class WalletConfigTest {

    /** application.yml alone (no real environment variables), plus the given overrides in front of it. */
    private static ConfigurableEnvironment environment(Map<String, Object> overrides) throws IOException {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        List<PropertySource<?>> yaml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        yaml.forEach(env.getPropertySources()::addLast);
        env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return env;
    }

    @Test
    @DisplayName("REQ-PF-004 the service binds to loopback by default")
    void bindsToLoopbackByDefault() throws IOException {
        assertThat(environment(Map.of()).getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("REQ-PF-004 WALLET_BIND overrides the bind address")
    void bindAddressIsOverridable() throws IOException {
        assertThat(environment(Map.of("WALLET_BIND", "0.0.0.0")).getProperty("server.address"))
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
    @DisplayName("REQ-PF-004 multipart parsing is off: no endpoint takes uploads, and a bad boundary must not reach it")
    void multipartIsOff() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.servlet.multipart.enabled"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("REQ-PF-030 every pooled connection starts with a lock_timeout, so a stuck row lock ends in an error")
    void connectionsSetLockTimeout() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.datasource.hikari.connection-init-sql"))
                .containsIgnoringCase("lock_timeout");
    }
}
