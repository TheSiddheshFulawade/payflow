package com.siddheshfulawade.payflow.risk.domain;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The payments of one payer that count towards velocity: payment id to the time (epoch millis) it was first allowed.
 *
 * <p>Invariant: a payment id is counted once, so checking a payment again is free (REQ-PF-032). An immutable value
 * without a clock: the same window and the same arguments give the same outcome on every cluster member.
 */
public record VelocityWindow(Map<UUID, Long> firstAllowed) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The window after a check, and the answer. */
    public record Outcome(VelocityWindow window, boolean allowed) {}

    public VelocityWindow {
        firstAllowed = Map.copyOf(firstAllowed);
    }

    public static VelocityWindow empty() {
        return new VelocityWindow(Map.of());
    }

    public int size() {
        return firstAllowed.size();
    }

    /** Checks one payment. Entries {@code windowMillis} old or older no longer count. */
    public Outcome add(UUID paymentId, long nowMillis, int limit, long windowMillis) {
        Map<UUID, Long> live = new HashMap<>(firstAllowed);
        live.values().removeIf(since -> nowMillis - since >= windowMillis);
        boolean counted = live.containsKey(paymentId);
        boolean allowed = counted || live.size() < limit;
        if (allowed && !counted) {
            // only a newly allowed payment is stored: a refused one takes no slot, a repeat keeps its first time
            live.put(paymentId, nowMillis);
        }
        return new Outcome(new VelocityWindow(live), allowed);
    }
}
