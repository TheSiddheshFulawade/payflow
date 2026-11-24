package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.StepDeadlines;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeadlinesProperties.class)
class PaymentConfig {

    @Bean
    StepDeadlines stepDeadlines(DeadlinesProperties properties) {
        return properties.toDeadlines();
    }
}
