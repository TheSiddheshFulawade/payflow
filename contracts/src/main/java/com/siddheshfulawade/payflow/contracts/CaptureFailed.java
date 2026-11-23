package com.siddheshfulawade.payflow.contracts;

import java.util.Objects;
import java.util.UUID;

/** The capture was refused (for example payee frozen or closed); the hold is untouched. */
public record CaptureFailed(UUID paymentId, String reason) implements Reply {

    public CaptureFailed {
        Objects.requireNonNull(paymentId, "paymentId");
        Checks.text(reason, "reason");
    }
}
