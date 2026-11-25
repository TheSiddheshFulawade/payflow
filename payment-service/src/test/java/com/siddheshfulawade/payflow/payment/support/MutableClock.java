package com.siddheshfulawade.payflow.payment.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The service clock in the integration tests: the system time until a test fixes it. Sweeper tests set a start
 * instant and move it forward, so "the deadline has passed" needs no waiting.
 */
public final class MutableClock extends Clock {

    private volatile Instant fixed;

    /** From now on {@link #instant()} returns this instant until it is moved. */
    public void set(Instant instant) {
        this.fixed = instant;
    }

    /** Moves the clock forward (a fixed clock stays fixed, a system clock becomes fixed at now + duration). */
    public synchronized void advance(Duration duration) {
        this.fixed = instant().plus(duration);
    }

    /** Back to the system time. */
    public void useSystemTime() {
        this.fixed = null;
    }

    @Override
    public Instant instant() {
        Instant fixedNow = fixed;
        return fixedNow != null ? fixedNow : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
