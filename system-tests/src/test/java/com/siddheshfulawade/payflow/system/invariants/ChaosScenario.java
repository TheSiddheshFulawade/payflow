package com.siddheshfulawade.payflow.system.invariants;

/**
 * The chaos scenarios of SPEC section 7, worded as the SPEC words them. After each one every payment must reach a
 * terminal state within 3 minutes and the money invariants must hold (REQ-PF-050). C6 (two payment-service
 * instances) is cut (SPEC section 8 cut line).
 */
public enum ChaosScenario {
    C1("kill wallet-service mid-batch and restart"),
    C2("restart Kafka broker"),
    C3("kill one risk member"),
    C4("pause payment-service past deadlines"),
    C5("client retries every request (same key)");

    private final String spec;

    ChaosScenario(String spec) {
        this.spec = spec;
    }

    public String id() {
        return name();
    }

    public String spec() {
        return spec;
    }
}
