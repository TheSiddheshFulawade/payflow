package com.siddheshfulawade.payflow.wallet.app;

import java.util.UUID;

/** One payment's money movement on the business date. Status is CAPTURED, RELEASED or REJECTED. */
public record StatementLine(UUID paymentId, long capturedAmount, String status) {}
