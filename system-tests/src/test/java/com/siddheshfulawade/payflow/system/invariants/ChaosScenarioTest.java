package com.siddheshfulawade.payflow.system.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChaosScenarioTest {

    @Test
    @DisplayName("REQ-PF-050 the scenarios are exactly C1 to C5 with their SPEC text")
    void listsC1ToC5() {
        assertThat(Arrays.stream(ChaosScenario.values()).map(ChaosScenario::id))
                .containsExactly("C1", "C2", "C3", "C4", "C5");
        assertThat(ChaosScenario.valueOf("C1").spec()).containsIgnoringCase("kill wallet-service");
        assertThat(ChaosScenario.valueOf("C2").spec()).containsIgnoringCase("restart Kafka");
        assertThat(ChaosScenario.valueOf("C3").spec()).containsIgnoringCase("risk member");
        assertThat(ChaosScenario.valueOf("C4").spec()).containsIgnoringCase("pause payment-service");
        assertThat(ChaosScenario.valueOf("C5").spec()).containsIgnoringCase("same key");
    }

    @Test
    @DisplayName("REQ-PF-050 C6 (two payment-service instances) is absent: cut, SPEC section 8")
    void c6IsCut() {
        assertThat(Arrays.stream(ChaosScenario.values()).map(Enum::name)).doesNotContain("C6");
        assertThat(Arrays.stream(ChaosScenario.values()).map(ChaosScenario::spec))
                .noneMatch(s -> s.toLowerCase().contains("two payment-service"));
    }
}
