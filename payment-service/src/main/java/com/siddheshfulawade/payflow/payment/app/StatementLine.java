package com.siddheshfulawade.payflow.payment.app;

import java.util.UUID;

/** One payment that reached a terminal state on the business date. Amount in minor units. */
public record StatementLine(UUID paymentId, long amount, String currency, String state) {}
