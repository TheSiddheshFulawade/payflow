package com.siddheshfulawade.payflow.recon.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdjacentDayEdgeTest {

    // TODO-ME: the same payment id appears twice in the adjacent wallet lines (the days D-1 and D+1) with different
    // content (for example CAPTURED 500 on one, RELEASED 0 on the other). StatementMatcher.lookupWallets
    // (StatementMatcher.java:89-100) must refuse it. Write the test body:
    //   1. build one payment line for the date D and no D wallet lines;
    //   2. pass two WalletLine objects with the same id and different content as the adjacentWallets argument of
    //      StatementMatcher.match(payments, wallets, adjacentPayments, adjacentWallets);
    //   3. assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class) and check the message names the id.
    // Then also assert that two IDENTICAL adjacent lines are accepted (no exception).
    // Check: remove @Disabled and run  mvnw.cmd -pl reconciliation test -Dtest=AdjacentDayEdgeTest
    // (it must pass); then change "!before.equals(line)" in lookupWallets to "false" and see it fail.
    @Test
    @Disabled("TODO-ME: write the body, then remove this line")
    @DisplayName("REQ-PF-040 the same id twice on the adjacent days with different content is refused")
    void adjacentWalletRepeatWithDifferentContentIsRefused() {}
}
