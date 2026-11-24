package com.siddheshfulawade.payflow.payment.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The sweeper's settings; the defaults are SPEC section 4's. A wrong setting stops the start: without a working sweeper
 * payments get stuck.
 *
 * @param enabled whether this instance sweeps on a schedule (tests turn it off and call the sweeper directly)
 * @param interval pause between sweeps
 * @param batchSize payments per sweep
 * @param maxResends re-sends of a step before its payment is released
 */
@ConfigurationProperties("payflow.payment.sweeper")
record SweeperProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration interval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("3") int maxResends) {

    SweeperProperties {
        require(interval.isPositive(), "interval must be positive");
        require(batchSize >= 1, "batch-size must be at least 1");
        require(maxResends >= 0, "max-resends must not be negative");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException("payflow.payment.sweeper: " + message);
        }
    }
}
