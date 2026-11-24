package com.siddheshfulawade.payflow.risk.support;

import com.hazelcast.core.HazelcastInstance;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.infra.HazelcastVelocityCounter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test doubles for the integration tests: a clock the test can set, and a velocity port that wraps the real
 * Hazelcast counter. Both are {@code @Primary}, so the service's own beans are bypassed only where a test needs it.
 * The real counter is built from the service's HazelcastInstance and RiskLimits beans.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RiskTestBeans {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    ControllableVelocityCounter testVelocityCounter(HazelcastInstance hazelcast, RiskLimits limits) {
        return new ControllableVelocityCounter(new HazelcastVelocityCounter(hazelcast, limits));
    }
}
