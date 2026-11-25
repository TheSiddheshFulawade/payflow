package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.config.Config;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The timings that decide how long a dead or hung member can hold up a check. No member is started. */
class HazelcastTimingConfigTest {

    private static Config config() {
        var properties = new HazelcastProperties("c", 5701, List.of("127.0.0.1:5701"), "127.0.0.1");
        return HazelcastConfigFactory.build(properties, RiskLimits.defaults());
    }

    @Test
    @DisplayName("REQ-PF-032 a hung member is noticed after 10 s: heartbeat every 1 s, 10 s without one is dead")
    void heartbeatSettings() {
        assertThat(config().getProperty("hazelcast.heartbeat.interval.seconds")).isEqualTo("1");
        assertThat(config().getProperty("hazelcast.max.no.heartbeat.seconds")).isEqualTo("10");
    }

    @Test
    @DisplayName("REQ-PF-032 Hazelcast's own call timeout stays 5 s; the 1 s bound of the counter is separate")
    void callTimeoutStaysFiveSeconds() {
        assertThat(config().getProperty("hazelcast.operation.call.timeout.millis"))
                .isEqualTo("5000");
        assertThat(HazelcastVelocityCounter.CALL_TIMEOUT).isLessThan(Duration.ofSeconds(5));
    }
}
