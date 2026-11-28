package com.siddheshfulawade.payflow.system.invariants;

/** One broken invariant: its name (a constant of InvariantChecker) and what was seen. */
public record Violation(String invariant, String detail) {}
