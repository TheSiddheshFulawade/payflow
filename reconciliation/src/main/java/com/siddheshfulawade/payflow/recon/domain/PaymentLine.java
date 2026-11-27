package com.siddheshfulawade.payflow.recon.domain;

import java.util.UUID;

/** A line of the payment statement. State is COMPLETED, REVERSED or REJECTED. Amount in minor units. */
public record PaymentLine(UUID paymentId, long amount, String currency, String state) {}
