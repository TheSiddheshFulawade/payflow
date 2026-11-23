package com.siddheshfulawade.payflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MessageCodecTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private final MessageCodec codec = new MessageCodec();

    static java.util.stream.Stream<Message> everyMessage() {
        return Samples.all().stream();
    }

    @ParameterizedTest
    @MethodSource("everyMessage")
    void REQ_PF_020_every_message_type_round_trips(Message payload) {
        Envelope original = Envelope.of(payload, 2, CLOCK);

        Envelope decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.payload()).isInstanceOf(payload.getClass());
    }

    @Test
    void REQ_PF_020_there_are_eleven_message_types() {
        assertThat(Samples.all()).map(Object::getClass).doesNotHaveDuplicates().hasSize(11);
    }

    @Test
    void REQ_PF_020_max_amount_round_trips_exactly_as_a_json_number() {
        long amount = Checks.MAX_AMOUNT;
        Envelope original = Envelope.of(new ReserveFunds(Samples.PAYMENT, "alice", amount, "INR"), 1, CLOCK);

        String json = codec.encode(original);
        Envelope decoded = codec.decode(json);

        assertThat(((ReserveFunds) decoded.payload()).amount()).isEqualTo(amount);
        assertThat(json).containsPattern("\"amount\"\\s*:\\s*" + amount + "\\b");
    }

    @Test
    void REQ_PF_020_envelope_carries_type_version_and_attempt_in_json() {
        String json = codec.encode(Envelope.of(new FundsReserved(Samples.PAYMENT), 3, CLOCK));

        assertThat(json)
                .containsPattern("\"type\"\\s*:\\s*\"FundsReserved\"")
                .containsPattern("\"version\"\\s*:\\s*1\\b")
                .containsPattern("\"attempt\"\\s*:\\s*3\\b");
    }

    @Test
    void REQ_PF_020_unknown_fields_are_tolerated_for_forward_compatibility() {
        Envelope original = Envelope.of(new FundsReserved(Samples.PAYMENT), 1, CLOCK);
        String json = codec.encode(original);
        // add an unknown field at the envelope level and inside the payload
        String extended = json.replaceFirst("\\{", "{\"futureField\":{\"a\":1},")
                .replaceFirst("\"payload\"\\s*:\\s*\\{", "\"payload\":{\"extra\":\"x\",");

        assertThat(codec.decode(extended)).isEqualTo(original);
    }

    @Test
    void REQ_PF_020_unknown_type_is_a_poison_message() {
        String json = codec.encode(Envelope.of(new FundsReserved(Samples.PAYMENT), 1, CLOCK))
                .replaceFirst("\"type\"\\s*:\\s*\"FundsReserved\"", "\"type\":\"Teleport\"");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void REQ_PF_020_missing_required_payload_field_is_a_poison_message() {
        String json = codec.encode(Envelope.of(new ReserveFunds(Samples.PAYMENT, "alice", 5, "INR"), 1, CLOCK))
                .replaceFirst(",?\\s*\"currency\"\\s*:\\s*\"INR\"", "");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void REQ_PF_020_missing_envelope_field_is_a_poison_message() {
        String json = codec.encode(Envelope.of(new FundsReserved(Samples.PAYMENT), 1, CLOCK))
                .replaceFirst("\"messageId\"\\s*:\\s*\"[^\"]+\",?", "");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void REQ_PF_020_malformed_json_is_a_poison_message() {
        assertThatThrownBy(() -> codec.decode("{not json")).isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> codec.decode("")).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void REQ_PF_020_version_higher_than_supported_is_a_poison_message() {
        String json = codec.encode(Envelope.of(new FundsReserved(Samples.PAYMENT), 1, CLOCK))
                .replaceFirst("\"version\"\\s*:\\s*1", "\"version\":2");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void REQ_PF_020_envelope_payment_id_must_match_payload_in_json_too() {
        UUID other = UUID.randomUUID();
        String json = codec.encode(Envelope.of(new FundsReserved(Samples.PAYMENT), 1, CLOCK))
                .replaceFirst("(\"payload\"\\s*:\\s*\\{[^}]*\"paymentId\"\\s*:\\s*\")[^\"]+", "$1" + other);

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    // TODO-ME: write this test, then remove @Disabled. Encode a valid FundsReserved envelope (as the tests above
    // do), replace its "attempt" value with -1 in the JSON (replaceFirst, like the version test), and assert that
    // codec.decode(json) throws PoisonMessageException. Check: run
    // `./mvnw -q -pl contracts test -Dtest=MessageCodecTest`; it must pass. Then temporarily delete the
    // `attempt < 1` check in Envelope and confirm your test fails (the Envelope constructor is what guards this).
    @org.junit.jupiter.api.Disabled("TODO-ME")
    @Test
    void REQ_PF_020_negative_attempt_is_a_poison_message() {}
}
