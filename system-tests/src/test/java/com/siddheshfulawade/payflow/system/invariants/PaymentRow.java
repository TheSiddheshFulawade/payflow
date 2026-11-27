package com.siddheshfulawade.payflow.system.invariants;

import java.util.UUID;

/** A payment row as the checker needs it. */
public record PaymentRow(
        UUID id, String clientId, String idempotencyKey, String payer, String payee, long amount, String state) {}
