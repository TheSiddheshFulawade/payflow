package com.siddheshfulawade.payflow.contracts;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What travels on Kafka: the message plus the metadata every consumer needs to stay correct under at-least-once
 * delivery.
 *
 * @param messageId unique per send; the consumer's inbox dedupes on it (a re-sent reply keeps its id)
 * @param paymentId the Kafka key, equal to {@code payload.paymentId()}
 * @param type the payload's type name, which selects the record class on decode
 * @param version the message format version, starting at 1
 * @param attempt the saga step attempt this message belongs to; the orchestrator ignores other attempts
 * @param occurredAt when the message was created
 * @param payload the command or reply
 */
public record Envelope(
        UUID messageId, UUID paymentId, String type, int version, int attempt, Instant occurredAt, Message payload) {

    /** The format version this code writes. {@link MessageCodec} refuses anything newer. */
    public static final int CURRENT_VERSION = 1;

    public Envelope {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, was " + version);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
        }
        // the key and the payload must name the same payment, or per-payment ordering breaks
        if (!paymentId.equals(payload.paymentId())) {
            throw new IllegalArgumentException(
                    "paymentId " + paymentId + " differs from payload " + payload.paymentId());
        }
        // the type selects the class on decode, so it must describe the payload
        if (!type.equals(typeOf(payload))) {
            throw new IllegalArgumentException("type " + type + " differs from payload " + typeOf(payload));
        }
    }

    /** A new message with a fresh id, the current version and the clock's time. */
    public static Envelope of(Message payload, int attempt, Clock clock) {
        Objects.requireNonNull(payload, "payload");
        return new Envelope(
                UUID.randomUUID(),
                payload.paymentId(),
                typeOf(payload),
                CURRENT_VERSION,
                attempt,
                clock.instant(),
                payload);
    }

    /** The wire name of a message type: the record's simple name, so renaming a record is a breaking change. */
    static String typeOf(Message payload) {
        return payload.getClass().getSimpleName();
    }
}
