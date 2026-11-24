package com.siddheshfulawade.payflow.risk.domain;

/** The rule that refused a payment (REQ-PF-031). The names travel in the replies and are stored with the decision. */
public enum RiskRule {
    SINGLE_LIMIT,
    DAILY_LIMIT,
    VELOCITY
}
