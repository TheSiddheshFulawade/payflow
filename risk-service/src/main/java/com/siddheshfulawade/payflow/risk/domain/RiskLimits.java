package com.siddheshfulawade.payflow.risk.domain;

import java.time.Duration;

/**
 * The limits of REQ-PF-031. All are inclusive: an amount or a count equal to the limit is still allowed.
 *
 * @param single largest single payment, in minor units
 * @param daily largest approved total of one payer per UTC date, in minor units
 * @param velocity most payments of one payer within {@code window}
 * @param window the sliding velocity window
 */
public record RiskLimits(long single, long daily, int velocity, Duration window) {

    public RiskLimits {
        // a limit of 0 must stop the service at startup instead of quietly refusing every payment
        if (single < 1 || daily < 1 || velocity < 1 || window.toMillis() < 1) {
            throw new IllegalArgumentException("risk limits must be positive");
        }
    }

    public static RiskLimits defaults() {
        return new RiskLimits(5_000_000, 20_000_000, 5, Duration.ofSeconds(60));
    }

    public boolean exceedsSingle(long amount) {
        return amount > single;
    }

    /** Whether {@code amount} on top of {@code total} crosses the daily limit (written so that it can't overflow). */
    public boolean exceedsDaily(long total, long amount) {
        return amount > daily - total;
    }
}
