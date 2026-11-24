package com.siddheshfulawade.payflow.risk.domain;

/** PENDING exists only inside the transaction that is deciding; it is never committed. The others are final. */
public enum Verdict {
    PENDING,
    APPROVED,
    REJECTED
}
