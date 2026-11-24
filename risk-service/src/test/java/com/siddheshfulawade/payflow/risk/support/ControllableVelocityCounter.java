package com.siddheshfulawade.payflow.risk.support;

import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import com.siddheshfulawade.payflow.risk.app.VelocityUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
        FAIL_AFTER_COUNTING,
        UNAVAILABLE_AFTER_DELAY
    }

    private final VelocityCounter real;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Instant> callTimes = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.DELEGATE;
    private volatile Duration delay = Duration.ZERO;

    public ControllableVelocityCounter(VelocityCounter real) {
        this.real = real;
    }

    @Override
    public boolean allow(String payer, UUID paymentId, Instant now) {
        calls.add(new Call(payer, paymentId));
        callTimes.add(Instant.now());
        return switch (mode) {
            case UNAVAILABLE_AFTER_DELAY -> {
                // the cost of the real counter's hard bound: it answers "unavailable" only after that long
                try {
                    TimeUnit.MILLISECONDS.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new VelocityUnavailableException(new IllegalStateException("velocity cluster did not answer"));
            }
            case DELEGATE -> real.allow(payer, paymentId, now);
            case ALLOW_ALL -> true;
            case FAIL_BEFORE_COUNTING -> throw new IllegalStateException("velocity cluster unreachable (test)");
            case FAIL_AFTER_COUNTING -> {
                real.allow(payer, paymentId, now);
                throw new IllegalStateException("crash after counting (test)");
            }
        };
    }

    /** Every check takes {@code after}, then fails like the real counter does when the cluster does not answer. */
    public void unavailableAfter(Duration after) {
        delay = after;
        mode = Mode.UNAVAILABLE_AFTER_DELAY;
    }

    /** When each call started, in call order. */
    public List<Instant> callTimes() {
        return List.copyOf(callTimes);
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
        callTimes.clear();
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
