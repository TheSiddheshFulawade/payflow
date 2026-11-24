package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({HazelcastProperties.class, RiskLimitsProperties.class})
class RiskConfig {

    @Bean
    RiskLimits riskLimits(RiskLimitsProperties properties) {
        return properties.toLimits();
    }

    // shutdown is the graceful one: it hands this member's partitions over before it leaves. Beans are destroyed after
    // the Kafka listeners have stopped, so no check is running any more.
    @Bean(destroyMethod = "shutdown")
    HazelcastInstance hazelcastInstance(HazelcastProperties properties, RiskLimits limits) {
        return Hazelcast.newHazelcastInstance(HazelcastConfigFactory.build(properties, limits));
    }
}
