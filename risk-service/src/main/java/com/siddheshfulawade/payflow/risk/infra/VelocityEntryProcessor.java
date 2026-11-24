package com.siddheshfulawade.payflow.risk.infra;

import com.hazelcast.map.EntryProcessor;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.util.Map;
import java.util.UUID;

/**
 * One velocity check, run on the member that owns the payer's key. Hazelcast runs the processors of one key one after
 * the other, so there is no read-modify-write race (REQ-PF-032).
 *
 * <p>Invariant: deterministic. The caller passes the time, so the backup member, which runs the same processor
 * ({@code getBackupProcessor} returns {@code this} by default), computes the same window as the owner.
 */
record VelocityEntryProcessor(UUID paymentId, long nowMillis, int limit, long windowMillis)
        implements EntryProcessor<String, VelocityWindow, Boolean> {

    private static final long serialVersionUID = 1L;

    @Override
    public Boolean process(Map.Entry<String, VelocityWindow> entry) {
        VelocityWindow before = entry.getValue() == null ? VelocityWindow.empty() : entry.getValue();
        VelocityWindow.Outcome outcome = before.add(paymentId, nowMillis, limit, windowMillis);
        // a refused or repeated check writes nothing: a write would restart the entry's TTL
        if (!outcome.window().equals(before)) {
            entry.setValue(outcome.window());
        }
        return outcome.allowed();
    }
}
