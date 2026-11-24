package com.siddheshfulawade.payflow.risk.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** The service clock in the integration tests: the system time until a test fixes it, e.g. around midnight UTC. */
public final class MutableClock extends Clock {

    private volatile Instant fixed;

    /** From now on {@link #instant()} returns this instant. */
    public void set(Instant instant) {
        this.fixed = instant;
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
