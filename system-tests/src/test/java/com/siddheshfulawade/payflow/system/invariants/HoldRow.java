package com.siddheshfulawade.payflow.system.invariants;

import java.util.UUID;

/** A hold row: state is RESERVED, RELEASED, CAPTURED or REJECTED. */
public record HoldRow(UUID paymentId, String state, long amount) {}
