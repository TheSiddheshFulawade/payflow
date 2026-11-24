package com.siddheshfulawade.payflow.risk.infra;

import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Overrides of the limits; the defaults are REQ-PF-031's, the same as {@link RiskLimits#defaults()} (tested). */
@ConfigurationProperties("payflow.risk.limits")
record RiskLimitsProperties(
        @DefaultValue("5000000") long single,
        @DefaultValue("20000000") long daily,
        @DefaultValue("5") int velocity,
        @DefaultValue("60s") Duration window) {

    RiskLimits toLimits() {
        return new RiskLimits(single, daily, velocity, window);
    }
}
