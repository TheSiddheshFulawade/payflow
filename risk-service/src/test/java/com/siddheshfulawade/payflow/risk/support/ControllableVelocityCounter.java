package com.siddheshfulawade.payflow.risk.support;

import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The velocity port in the integration tests: by default it asks the real Hazelcast counter, and records every call.
 * A test can switch it to "allow everything" (the daily total is the thing under test), make it fail before it counts
 * (cluster unreachable), or fail after it counted (the crash between the count and the database commit).
 */
public final class ControllableVelocityCounter implements VelocityCounter {

    public record Call(String payer, UUID paymentId) {}

    private enum Mode {
        DELEGATE,
        ALLOW_ALL,
        FAIL_BEFORE_COUNTING,
        FAIL_AFTER_COUNTING
    }

    private final VelocityCounter real;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.DELEGATE;

    public ControllableVelocityCounter(VelocityCounter real) {
        this.real = real;
    }

    @Override
    public boolean allow(String payer, UUID paymentId, Instant now) {
        calls.add(new Call(payer, paymentId));
        return switch (mode) {
            case DELEGATE -> real.allow(payer, paymentId, now);
            case ALLOW_ALL -> true;
            case FAIL_BEFORE_COUNTING -> throw new IllegalStateException("velocity cluster unreachable (test)");
            case FAIL_AFTER_COUNTING -> {
                real.allow(payer, paymentId, now);
                throw new IllegalStateException("crash after counting (test)");
            }
        };
    }

    public void allowAll() {
        mode = Mode.ALLOW_ALL;
    }

    public void failBeforeCounting() {
        mode = Mode.FAIL_BEFORE_COUNTING;
    }

    public void failAfterCounting() {
        mode = Mode.FAIL_AFTER_COUNTING;
    }

    /** Back to the real counter, and forget the calls. */
    public void reset() {
        mode = Mode.DELEGATE;
        calls.clear();
    }

    /** Back to the real counter, keeping the recorded calls. */
    public void recover() {
        mode = Mode.DELEGATE;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public long callsFor(String payer) {
        return calls.stream().filter(c -> c.payer().equals(payer)).count();
    }
}
