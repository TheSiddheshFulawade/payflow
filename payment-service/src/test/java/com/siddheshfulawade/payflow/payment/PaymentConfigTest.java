package com.siddheshfulawade.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class PaymentConfigTest {

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
    @DisplayName("REQ-PF-004 the service binds to loopback by default (no authentication until PF-07)")
    void bindsToLoopbackByDefault() throws IOException {
        assertThat(environment(Map.of()).getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("REQ-PF-004 PAYMENT_BIND overrides the bind address")
    void bindAddressIsOverridable() throws IOException {
        assertThat(environment(Map.of("PAYMENT_BIND", "0.0.0.0")).getProperty("server.address"))
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
    @DisplayName("REQ-PF-004 the database user and url are overridable, and the password has no default")
    void passwordHasNoDefault() throws IOException {
        assertThat(environment(Map.of("PAYMENT_DB_USER", "other")).getProperty("spring.datasource.username"))
                .isEqualTo("other");
        assertThat(environment(Map.of("PAYMENT_DB_PASSWORD", "from-env")).getProperty("spring.datasource.password"))
                .isEqualTo("from-env");

        assertThatThrownBy(() -> environment(Map.of()).getProperty("spring.datasource.password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("REQ-PF-014 every pooled connection starts with a lock_timeout, so a stuck row lock ends in an error")
    void connectionsSetLockTimeout() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.datasource.hikari.connection-init-sql"))
                .containsIgnoringCase("lock_timeout")
                .contains("5s");
    }

    @Test
    @DisplayName("REQ-PF-020 Flyway reads the messaging tables (V1) and this service's own scripts")
    void flywayLocations() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.flyway.locations"))
                .contains("classpath:db/messaging")
                .contains("classpath:db/migration");
    }

    @Test
    @DisplayName("REQ-PF-004 Boot's form content filter is off so the body limit decides on size before any read")
    void formFilterIsOff() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.mvc.formcontent.filter.enabled"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("REQ-PF-004 multipart parsing is off: no endpoint takes uploads, and a bad boundary must not reach it")
    void multipartIsOff() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.servlet.multipart.enabled"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("REQ-PF-004 HTTP/2 is off: the body limit relies on HTTP/1.1 rules for a request without a length")
    void http2IsOff() throws IOException {
        assertThat(environment(Map.of()).getProperty("server.http2.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("REQ-PF-012 three scheduler threads: a relay blocked on Kafka must not stop the sweeper")
    void schedulerHasThreeThreads() throws IOException {
        assertThat(environment(Map.of()).getProperty("spring.task.scheduling.pool.size"))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("REQ-PF-010 the service listens on 8081")
    void port() throws IOException {
        assertThat(environment(Map.of()).getProperty("server.port")).isEqualTo("8081");
    }
}
