package com.siddheshfulawade.payflow.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Starts the real application.yml with this service's security configuration (no database, Kafka or messaging).
 * Operator mistake: PAYFLOW_ISSUER_URI forgotten. The service must not come up, because a resource server with no
 * issuer would have nothing to check tokens against.
 */
class MissingIssuerStartupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(SecurityConfig.class)
    static class SecurityOnly {}

    private static ConfigurableApplicationContext start(String... extraArgs) {
        String[] fixed = {
            // only the production file: the test application.properties would name an issuer
            "--spring.config.location=classpath:/application.yml",
            "--server.port=0",
            "--spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                    + "org.springframework.boot.hazelcast.autoconfigure.HazelcastAutoConfiguration,"
                    + "com.siddheshfulawade.payflow.messaging.MessagingAutoConfiguration"
        };
        String[] args = new String[fixed.length + extraArgs.length];
        System.arraycopy(fixed, 0, args, 0, fixed.length);
        System.arraycopy(extraArgs, 0, args, fixed.length, extraArgs.length);
        return new SpringApplicationBuilder(SecurityOnly.class)
                .web(WebApplicationType.SERVLET)
                .run(args);
    }

    private static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    @DisplayName("REQ-PF-003 control: with PAYFLOW_ISSUER_URI set the same start-up succeeds")
    void startsWithIssuer() {
        assumeThat(System.getenv("PAYFLOW_ISSUER_URI")).isNull();

        try (ConfigurableApplicationContext context =
                start("--PAYFLOW_ISSUER_URI=http://keycloak.test/realms/payflow")) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    @DisplayName("REQ-PF-003 a missing PAYFLOW_ISSUER_URI fails the application start and names the setting")
    void missingIssuerFailsStart() {
        assumeThat(System.getenv("PAYFLOW_ISSUER_URI")).isNull();

        assertThatThrownBy(() -> start().close())
                .as("start-up without an issuer")
                .satisfies(failure -> assertThat(stackTrace(failure)).contains("PAYFLOW_ISSUER_URI"));
    }

    @Test
    @DisplayName("REQ-PF-003 a BLANK issuer with a jwk-set-uri set fails the start (no silent loss of the iss check)")
    void blankIssuerWithJwkSetUriFailsStart() {
        assumeThat(System.getenv("PAYFLOW_ISSUER_URI")).isNull();

        // Boot adds the issuer validator only when the issuer has text, so blank + jwk-set-uri would accept any iss
        assertThatThrownBy(() -> start(
                                "--PAYFLOW_ISSUER_URI=",
                                "--PAYFLOW_JWK_SET_URI=http://keycloak.test/realms/payflow/protocol/openid-connect/certs")
                        .close())
                .as("start-up with a blank issuer")
                .isInstanceOf(Exception.class);
    }
}
