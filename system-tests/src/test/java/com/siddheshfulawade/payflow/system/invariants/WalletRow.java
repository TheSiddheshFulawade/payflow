package com.siddheshfulawade.payflow.system.invariants;

/** A wallet row, minor units as long (REQ-PF-030). */
public record WalletRow(String id, long available, long held) {}
