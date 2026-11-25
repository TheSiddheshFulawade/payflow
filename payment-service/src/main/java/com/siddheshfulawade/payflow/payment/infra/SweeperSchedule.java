package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.PaymentSweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Runs the sweeper every {@code payflow.payment.sweeper.interval}, when enabled. */
// messaging enables scheduling too; stated here as well, because without it payments would silently get stuck
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SweeperSchedule implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SweeperSchedule.class);

    private final PaymentSweeper sweeper;
    private final SweeperProperties settings;

    SweeperSchedule(PaymentSweeper sweeper, SweeperProperties settings) {
        this.sweeper = sweeper;
        this.settings = settings;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (settings.enabled()) {
            // fixed delay, not fixed rate: sweeps never overlap within one instance (SKIP LOCKED covers other ones)
            registrar.addFixedDelayTask(this::sweep, settings.interval());
        } else {
            log.warn("the payment sweeper is disabled on this instance: if no other one sweeps, payments get stuck");
        }
    }

    private void sweep() {
        try {
            sweeper.sweep();
        } catch (RuntimeException e) {
            log.warn("sweep failed; overdue payments stay overdue and the next sweep takes them", e);
        }
    }
}
