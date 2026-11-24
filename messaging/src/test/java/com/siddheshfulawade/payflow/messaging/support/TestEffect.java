package com.siddheshfulawade.payflow.messaging.support;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** The business effect under test: inserts an effect row, replies FundsReserved, can fail on demand. */
public final class TestEffect {

    private final JdbcTemplate jdbc;
    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private volatile Supplier<RuntimeException> failure = () -> new IllegalStateException("simulated database failure");

    public TestEffect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Envelope> apply(Envelope in) {
        invocations.incrementAndGet();
        jdbc.update("insert into effect(payment_id, message_id) values (?, ?)", in.paymentId(), in.messageId());
        // fail AFTER the insert, so only a rollback keeps the effect table at one row
        if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw failure.get();
        }
        return Optional.of(Envelope.of(new FundsReserved(in.paymentId()), in.attempt(), Clock.systemUTC()));
    }

    /** An effect that does nothing and sends no reply. */
    public Optional<Envelope> applyWithoutReply(Envelope in) {
        invocations.incrementAndGet();
        return Optional.empty();
    }

    public void failNextCalls(int n) {
        failNextCalls(n, () -> new IllegalStateException("simulated database failure"));
    }

    /** The next {@code n} calls fail with this exception (after their insert, which must roll back). */
    public void failNextCalls(int n, Supplier<RuntimeException> exception) {
        failure = exception;
        failuresRemaining.set(n);
    }

    public int invocations() {
        return invocations.get();
    }

    public void reset() {
        invocations.set(0);
        failuresRemaining.set(0);
    }
}
