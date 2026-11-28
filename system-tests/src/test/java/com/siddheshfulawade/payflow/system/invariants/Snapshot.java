package com.siddheshfulawade.payflow.system.invariants;

import java.util.List;

/** Everything read from the wallet and payment databases after a run, plus the total seeded into wallets. */
public record Snapshot(
        List<WalletRow> wallets,
        List<HoldRow> holds,
        List<LedgerRow> ledger,
        List<PaymentRow> payments,
        long seededTotal) {}
