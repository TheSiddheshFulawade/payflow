package com.siddheshfulawade.payflow.recon.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * REQ-PF-040: binding only, no database. The ops token has no default: a job that starts without it would download
 * nothing but 401 pages, so the start itself must fail. Needs Bean Validation on the classpath (ReconProperties is
 * {@code @Validated}, opsToken {@code @NotBlank}).
 */
class ReconPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReconProperties.class)
    static class Binding {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class)
            .withPropertyValues(
                    "payflow.recon.payment-url=http://payment:8081", "payflow.recon.wallet-url=http://wallet:8082");

    @Test
    @DisplayName("REQ-PF-040 a missing ops token fails the start")
    void missingToken() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("REQ-PF-040 with the shipped application.yml and RECON_OPS_TOKEN not set, the start fails"
            + " (an unresolved placeholder must not pass as a token)")
    void unsetEnvironmentVariableFailsWithShippedConfig() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("RECON_OPS_TOKEN") == null, "RECON_OPS_TOKEN is set in this environment");
        new ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Binding.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @DisplayName("REQ-PF-040 a size cap that is not positive fails the start")
    void sizeCapMustBePositive(String cap) {
        runner.withPropertyValues("payflow.recon.ops-token=t", "payflow.recon.max-statement-bytes=" + cap)
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("REQ-PF-040 a blank ops token fails the start")
    void blankToken(String token) {
        runner.withPropertyValues("payflow.recon.ops-token=" + token)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("REQ-PF-040 a token starts it; defaults are 64 MiB for a statement and INR for the currency")
    void tokenAndDefaults() {
        runner.withPropertyValues("payflow.recon.ops-token=t").run(context -> {
            assertThat(context).hasNotFailed();
            ReconProperties properties = context.getBean(ReconProperties.class);
            assertThat(properties.maxStatementBytes()).isEqualTo(64L * 1024 * 1024);
            assertThat(properties.currency()).isEqualTo("INR");
            assertThat(properties.toString()).doesNotContain("opsToken=t").contains("opsToken=***");
        });
    }

    @Test
    @DisplayName("REQ-PF-040 the size cap and the currency can be configured")
    void overrides() {
        runner.withPropertyValues(
                        "payflow.recon.ops-token=t",
                        "payflow.recon.max-statement-bytes=1000",
                        "payflow.recon.currency=USD")
                .run(context -> {
                    ReconProperties properties = context.getBean(ReconProperties.class);
                    assertThat(properties.maxStatementBytes()).isEqualTo(1000);
                    assertThat(properties.currency()).isEqualTo("USD");
                });
    }
}
