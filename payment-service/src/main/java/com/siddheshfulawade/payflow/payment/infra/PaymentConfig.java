package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.messaging.OutboxWriter;
import com.siddheshfulawade.payflow.payment.app.PaymentSweeper;
import com.siddheshfulawade.payflow.payment.app.Payments;
import com.siddheshfulawade.payflow.payment.app.StepDeadlines;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DeadlinesProperties.class, SweeperProperties.class})
class PaymentConfig {

    @Bean
    StepDeadlines stepDeadlines(DeadlinesProperties properties) {
        return properties.toDeadlines();
    }

    @Bean
    PaymentSweeper paymentSweeper(
            Payments payments,
            OutboxWriter outbox,
            StepDeadlines deadlines,
            Clock clock,
            PlatformTransactionManager transactionManager,
            SweeperProperties properties) {
        return new PaymentSweeper(
                payments,
                outbox,
                deadlines,
                clock,
                transactionManager,
                properties.batchSize(),
                properties.maxResends());
    }
}
