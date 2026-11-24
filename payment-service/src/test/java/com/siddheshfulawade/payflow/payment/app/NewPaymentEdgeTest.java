package com.siddheshfulawade.payflow.payment.app;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class NewPaymentEdgeTest {

    // TODO-ME: NewPayment.requestHash() (NewPayment.java:23-33) writes each field as "length:value". Show that a
    // field which itself looks like "length:value" cannot fake a boundary. Take payer "x1:y" with payee "z" against
    // payer "x" with payee "1:yz": joined with no prefix both read "x1:yz" (work out on paper what "length:value"
    // gives for each). Assert with AssertJ that their requestHash() values differ (same amount and currency). Add a
    // second pair of your own that moves a colon or digit between payee and amount, and a third assertion that the
    // same object always gives the same hash. Name the rule in a @DisplayName ("REQ-PF-002 ..."). Remove @Disabled.
    // Check: ./mvnw -q -B clean test -pl payment-service -am -Dtest=NewPaymentEdgeTest
    // (green with your assertions; temporarily change the mapping in requestHash() to `.map(field -> field)` and the
    // first assertion must go red; revert it afterwards).
    @Test
    @Disabled("TODO-ME: write the assertions, then remove this line")
    void lengthPrefixStopsFakeBoundaries() {}
}
