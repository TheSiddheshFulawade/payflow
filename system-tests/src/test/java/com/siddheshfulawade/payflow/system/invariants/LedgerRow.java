package com.siddheshfulawade.payflow.system.invariants;

import java.util.UUID;

/** A ledger_entry row; paymentId is null only for kind SEED. */
public record LedgerRow(UUID paymentId, String walletId, String kind, long amount) {}
