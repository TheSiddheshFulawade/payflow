package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RiskLimitsPropertiesTest {

    private static RiskLimits limits(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("payflow.risk.limits", RiskLimitsProperties.class)
                .toLimits();
    }

    @Test
    @DisplayName("REQ-PF-031 without properties the limits are the ones of the spec")
    void defaults() {
        assertThat(limits(Map.of()))
                .isEqualTo(RiskLimits.defaults())
                .isEqualTo(new RiskLimits(5_000_000, 20_000_000, 5, Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("REQ-PF-031 one limit can be overridden, the others keep their defaults")
    void override() {
        assertThat(limits(Map.of("payflow.risk.limits.velocity", "7")))
                .isEqualTo(new RiskLimits(5_000_000, 20_000_000, 7, Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("REQ-PF-031 a limit that is not positive stops the service at startup")
    void notPositive() {
        for (String name : new String[] {"single", "daily", "velocity", "window"}) {
            assertThatIllegalArgumentException()
                    .as(name)
                    .isThrownBy(() -> limits(Map.of("payflow.risk.limits." + name, "0")));
        }
    }
}
