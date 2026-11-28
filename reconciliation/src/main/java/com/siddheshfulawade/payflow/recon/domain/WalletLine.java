package com.siddheshfulawade.payflow.recon.domain;

import java.util.UUID;

/** A line of the wallet statement. Status is CAPTURED, RELEASED or REJECTED; the amount is what was captured. */
public record WalletLine(UUID paymentId, long capturedAmount, String status) {}
