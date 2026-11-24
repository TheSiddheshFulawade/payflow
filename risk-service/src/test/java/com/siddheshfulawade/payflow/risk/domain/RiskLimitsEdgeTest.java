package com.siddheshfulawade.payflow.risk.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class RiskLimitsEdgeTest {

    // TODO-ME: the daily check is written as `amount > daily - total` (RiskLimits.exceedsDaily, RiskLimits.java:31-33),
    // not `total + amount > daily`. Write the test that shows why. Use RiskLimits.defaults() (daily = 20,000,000) and
    // assert with AssertJ: (1) exceedsDaily(20_000_000, 1) is true and exceedsDaily(20_000_000, 0) is false (inclusive
    // limit), (2) exceedsDaily(10_000_000, Long.MAX_VALUE) is true. Then, on paper, say what `total + amount > daily`
    // would return for (2) (hint: long overflow). Name the rule in a @DisplayName ("REQ-PF-031 ..."). Remove @Disabled.
    // Check: ./mvnw -q -B clean test -pl risk-service -am -Dtest=RiskLimitsEdgeTest
    // (green with your assertions; temporarily change exceedsDaily to `total + amount > daily` and (2) must go red).
    @Test
    @Disabled("TODO-ME: write the assertions, then remove this line")
    void dailyLimitIsInclusiveAndCannotOverflow() {}
}
