package com.siddheshfulawade.payflow.payment.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The sweeper's settings; the defaults are SPEC section 4's. A wrong setting stops the start: without a working sweeper
 * payments get stuck.
 *
 * @param enabled whether this instance sweeps on a schedule (tests turn it off and call the sweeper directly)
 * @param interval pause between sweeps, 100 ms to 1 min: neither a busy loop on the database nor hardly ever
 * @param batchSize payments per sweep, 1 to 1000
 * @param maxResends re-sends of a step before its payment is released, 0 to 20
 */
@ConfigurationProperties("payflow.payment.sweeper")
record SweeperProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration interval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("3") int maxResends) {

    static final Duration MIN_INTERVAL = Duration.ofMillis(100);
    static final Duration MAX_INTERVAL = Duration.ofMinutes(1);

    SweeperProperties {
        require(
                interval.compareTo(MIN_INTERVAL) >= 0 && interval.compareTo(MAX_INTERVAL) <= 0,
                "interval must be 100ms to 1m");
        require(batchSize >= 1 && batchSize <= 1_000, "batch-size must be 1 to 1000");
        require(maxResends >= 0 && maxResends <= 20, "max-resends must be 0 to 20");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException("payflow.payment.sweeper: " + message);
        }
    }
}
