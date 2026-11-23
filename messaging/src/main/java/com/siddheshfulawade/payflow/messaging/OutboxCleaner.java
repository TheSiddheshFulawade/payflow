package com.siddheshfulawade.payflow.messaging;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Deletes outbox rows that were sent long ago, so the table does not grow forever. */
public class OutboxCleaner {

    /**
     * How long a sent row is kept. It is also how long a duplicate can still get its stored reply re-sent; it
     * matches Kafka's default retention (7 days), after which a record can no longer be redelivered anyway.
     */
    public static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcClient jdbc;

    public OutboxCleaner(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes sent rows older than {@code age}; unsent rows are never touched. Returns how many were deleted. */
    public int deleteSentOlderThan(Duration age) {
        // sent_at is not null: an unsent message is never lost, however old it is
        return jdbc.sql(
                        "delete from outbox where sent_at is not null and sent_at < now() - :seconds * interval '1 second'")
                .param("seconds", age.toSeconds())
                .update();
    }
}
