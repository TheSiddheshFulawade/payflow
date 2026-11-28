package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SPEC section 7, level "System": one payment per outcome through the whole compose stack, with the amounts of
 * deploy/demo.ps1 (payer 6,000,000, payee 5,000). Each test ends with the invariant checker.
 */
@Tag("system")
class EndToEndSystemTest {

    private static final long PAYER_BALANCE = 6_000_000;
    private static final long PAYEE_BALANCE = 5_000;

    @Test
    @DisplayName("REQ-PF-010 a payment within every limit is COMPLETED, captured once")
    void happyPathCompletes() throws Exception {
        Map<String, Long> outcome = sendOne("e2e-ok", 2_500, "COMPLETED");

        assertThat(outcome).containsEntry("COMPLETED", 1L);
    }

    @Test
    @DisplayName("REQ-PF-011 a payment over the single-payment risk limit is held, then REVERSED with nothing moved")
    void riskRejectIsReversed() throws Exception {
        // 5,000,001 is one over the limit of REQ-PF-031: the wallet holds it (the payer has enough), risk says no
        Map<String, Long> outcome = sendOne("e2e-risk", 5_000_001, "REVERSED");

        assertThat(outcome).containsEntry("REVERSED", 1L);
    }

    @Test
    @DisplayName("REQ-PF-010 a payment above the balance of the payer is REJECTED by the wallet, nothing held")
    void insufficientFundsIsRejected() throws Exception {
        Map<String, Long> outcome = sendOne("e2e-funds", 9_000_000, "REJECTED");

        assertThat(outcome).containsEntry("REJECTED", 1L);
    }

    private static Map<String, Long> sendOne(String name, long amount, String expectedState) throws Exception {
        Scenario scenario = new Scenario(name);
        String payer = scenario.seed("payer", PAYER_BALANCE);
        String payee = scenario.seed("payee", PAYEE_BALANCE);
        Scenario.Spec spec = scenario.spec(payer, payee, amount);

        scenario.sendAsync(List.of(spec), 1).get();

        String id = scenario.paymentIdOf(spec);
        assertThat(id).as("payment id returned for the request").isNotBlank();
        // what the client sees through the API must match what the databases say
        await().atMost(Scenario.TERMINAL_WITHIN)
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(scenario.stateOf(id)).isEqualTo(expectedState));
        return scenario.assertInvariants(1);
    }
}
