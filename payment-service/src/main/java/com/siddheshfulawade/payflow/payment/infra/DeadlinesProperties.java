package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.StepDeadlines;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Overrides of the step deadlines; the defaults are SPEC section 4's. */
@ConfigurationProperties("payflow.payment.deadlines")
record DeadlinesProperties(
        @DefaultValue("10s") Duration reserve,
        @DefaultValue("5s") Duration risk,
        @DefaultValue("10s") Duration capture) {

    StepDeadlines toDeadlines() {
        return new StepDeadlines(reserve, risk, capture);
    }
}
