package com.siddheshfulawade.payflow.payment.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class ReleaseAttemptEdgeTest {

    // TODO-ME: outside RELEASING a reply counts only for the current attempt (SagaStateMachine.on, the
    // `replyAttempt != payment.stepAttempts()` guard). Build a RISK_CHECKING payment on attempt 2 with
    // PaymentFixtures.payment(PaymentState.RISK_CHECKING, 2). Assert with AssertJ that
    // SagaStateMachine.on(payment, new RiskApproved(payment.id()), 1) is empty (a late answer to the first check), that
    // the same reply with attempt 2 gives a transition to CAPTURING that sends a CaptureFunds, and that attempt 3 is
    // empty too. Name the rule in a @DisplayName ("REQ-PF-013 ..."). Remove @Disabled.
    // Check: ./mvnw -q -B clean test -pl payment-service -am -Dtest=ReleaseAttemptEdgeTest
    // (green with your assertions; temporarily change `replyAttempt != payment.stepAttempts()` in
    // SagaStateMachine.on to `false` and the first assertion must go red; revert it afterwards).
    @Test
    @Disabled("TODO-ME: write the assertions, then remove this line")
    void onlyTheCurrentAttemptCountsOutsideReleasing() {}
}
