package com.siddheshfulawade.payflow.wallet.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class WalletRefusalOrderTest {

    // TODO-ME: a FROZEN wallet that also has too little money: which reason does it give? Read
    // Wallet.refusesReserve (Wallet.java:9-15), decide which reason the caller should see, then write the test:
    // build new Wallet("alice", 10, WalletStatus.FROZEN), ask refusesReserve(500), assert the reason with AssertJ
    // (assertThat(...).contains(...)), and name the rule in a @DisplayName. Remove @Disabled.
    // Check: ./mvnw -q -B clean test -pl wallet-service -am -Dtest=WalletRefusalOrderTest
    // (green with your assertion; flip the assertion to the other reason and it must go red).
    @Test
    @Disabled("TODO-ME: write the assertion, then remove this line")
    void frozenAndPoorGivesOneReason() {}
}
