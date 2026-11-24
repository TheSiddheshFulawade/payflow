package com.siddheshfulawade.payflow.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test double for the integration tests: a clock the test can set. It is primary, so the service's own is bypassed. */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentTestBeans {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
