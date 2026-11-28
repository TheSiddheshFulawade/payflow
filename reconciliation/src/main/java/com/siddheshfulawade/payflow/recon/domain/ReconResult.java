package com.siddheshfulawade.payflow.recon.domain;

import java.util.UUID;

/** One report row. */
public record ReconResult(UUID paymentId, Outcome outcome) {}
