package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.payment.app.StepDeadlines;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** payflow.payment.deadlines.{reserve,risk,capture,release,cap} and their defaults (SPEC section 4). */
class DeadlinesPropertiesTest {

    private static StepDeadlines bound(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("payflow.payment.deadlines", DeadlinesProperties.class)
                .toDeadlines();
    }

    @Test
    @DisplayName("REQ-PF-012 without settings the deadlines are 10 s, 5 s, 10 s, release 10 s, cap 30 s")
    void defaults() {
        assertThat(bound(Map.of()))
                .isEqualTo(new StepDeadlines(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("REQ-PF-012 every deadline is configurable")
    void everySettingIsBound() {
        StepDeadlines deadlines = bound(Map.of(
                "payflow.payment.deadlines.reserve", "3s",
                "payflow.payment.deadlines.risk", "2s",
                "payflow.payment.deadlines.capture", "4s",
                "payflow.payment.deadlines.release", "6s",
                "payflow.payment.deadlines.cap", "7s"));

        assertThat(deadlines)
                .isEqualTo(new StepDeadlines(
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(6),
                        Duration.ofSeconds(7)));
    }

    @Test
    @DisplayName("REQ-PF-012 a non-positive release or cap stops the start: the service never runs without deadlines")
    void nonPositiveIsRefused() {
        assertThatThrownBy(() -> bound(Map.of("payflow.payment.deadlines.release", "0s")))
                .hasStackTraceContaining("release");
        assertThatThrownBy(() -> bound(Map.of("payflow.payment.deadlines.cap", "-1s")))
                .hasStackTraceContaining("cap");
    }
}
