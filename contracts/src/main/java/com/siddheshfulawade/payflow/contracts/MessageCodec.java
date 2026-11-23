package com.siddheshfulawade.payflow.contracts;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Envelope to JSON and back. Thread-safe: create one and share it.
 *
 * <p>Wire format (flat): {@code messageId, paymentId, type, version, attempt, occurredAt, payload{...}}. Unknown
 * fields are ignored, so a newer sender may add fields. Anything that cannot become a valid {@link Envelope} is a
 * {@link PoisonMessageException}.
 *
 * <p>Decoding is strict: a hostile or buggy sender can only ever cause a poison message, never a different value
 * (no number from a string, no truncated decimal, no "last duplicate key wins") and never unbounded work.
 */
public final class MessageCodec {

    /** Largest accepted document. Records are bounded, so a real message is a few hundred bytes. */
    static final long MAX_DOCUMENT_CHARS = 64 * 1024L;

    /** Deepest accepted JSON nesting. Our own messages use 2 levels; the rest is room for future fields. */
    static final int MAX_NESTING_DEPTH = 16;

    /**
     * Type name to record class, built from the sealed hierarchy, so the set is closed and complete. The payload class
     * comes only from this map, never from the JSON: no polymorphic default typing, so no gadget classes.
     */
    private static final Map<String, Class<? extends Message>> TYPES = Stream.of(
                    Command.class.getPermittedSubclasses(), Reply.class.getPermittedSubclasses())
            .flatMap(Arrays::stream)
            .map(c -> c.asSubclass(Message.class))
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    /** A path segment is reported only if it looks like one of our field names; anything else becomes {@code ?}. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,31}");

    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    // bound the work one record can cause before any field is looked at
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(MAX_DOCUMENT_CHARS)
                            .maxNestingDepth(MAX_NESTING_DEPTH)
                            .build())
                    .build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // every record component is required: a missing field fails instead of silently becoming null or 0
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            // {"amount":1,"amount":999999} fails; otherwise two readers could disagree on which amount is meant
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            // 100.99 never becomes 100, "100" never becomes 100, 12345 never becomes "12345"
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(
                    LogicalType.Integer,
                    c -> c.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(
                    LogicalType.Textual,
                    c -> c.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    public String encode(Envelope envelope) {
        Wire wire = new Wire(
                envelope.messageId(),
                envelope.paymentId(),
                envelope.type(),
                envelope.version(),
                envelope.attempt(),
                envelope.occurredAt(),
                mapper.valueToTree(envelope.payload()));
        return mapper.writeValueAsString(wire);
    }

    /**
     * Reads one envelope.
     *
     * <p>Error messages are log-safe: a fixed reason and the JSON path of the bad field, never a value from the
     * document and never a library message (that one stays in the cause, which is not logged as text).
     *
     * @throws PoisonMessageException if the JSON is not a valid envelope that this code understands
     */
    public Envelope decode(String json) {
        if (json == null) {
            throw new PoisonMessageException("cannot decode message: no content");
        }
        Wire wire = read(() -> mapper.readValue(json, Wire.class), "");
        if (wire.version() > Envelope.CURRENT_VERSION) {
            throw new PoisonMessageException("cannot decode message: unsupported version at version");
        }
        // the type string is the sender's text: it selects a class from the closed map and is never echoed
        Class<? extends Message> type = TYPES.get(wire.type());
        if (type == null) {
            throw new PoisonMessageException("cannot decode message: unknown message type at type");
        }
        Message payload = read(() -> mapper.treeToValue(wire.payload(), type), "payload");
        try {
            return new Envelope(
                    wire.messageId(),
                    wire.paymentId(),
                    wire.type(),
                    wire.version(),
                    wire.attempt(),
                    wire.occurredAt(),
                    payload);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PoisonMessageException("cannot decode message: invalid envelope", e);
        }
    }

    /** Runs one Jackson read; any failure becomes a poison with a fixed reason and the path below {@code prefix}. */
    private static <T> T read(Supplier<T> reading, String prefix) {
        try {
            return reading.get();
        } catch (JacksonException e) {
            Checks.InvalidField invalid = invalidField(e);
            if (invalid != null) {
                // a record rejected the value: name the record component, not the value
                throw poison("invalid field", join(prefix, invalid.field()), e);
            }
            String reason = e instanceof StreamReadException ? "malformed or oversized JSON" : "invalid field";
            throw poison(reason, join(prefix, path(e)), e);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw poison("invalid field", prefix, e);
        }
    }

    private static PoisonMessageException poison(String reason, String path, Throwable cause) {
        String where = path.isEmpty() ? "$" : path;
        return new PoisonMessageException("cannot decode message: " + reason + " at " + where, cause);
    }

    private static Checks.InvalidField invalidField(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Checks.InvalidField invalid) {
                return invalid;
            }
        }
        return null;
    }

    /** {@code payer}, {@code payload.amount}, {@code list[2]}: built from field names only, never from values. */
    private static String path(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            String name = ref.getPropertyName();
            if (name != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(SAFE_NAME.matcher(name).matches() ? name : "?");
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String join(String prefix, String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        return path.isEmpty() ? prefix : prefix + "." + path;
    }

    /** The JSON shape. The payload stays a tree until {@code type} has picked its class. */
    record Wire(
            UUID messageId,
            UUID paymentId,
            String type,
            int version,
            int attempt,
            Instant occurredAt,
            JsonNode payload) {}
}
