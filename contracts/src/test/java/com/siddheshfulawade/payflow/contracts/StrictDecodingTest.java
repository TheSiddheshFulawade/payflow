package com.siddheshfulawade.payflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Strict decoding, record bounds and log-safe errors: a hostile or buggy sender must only ever cause a poison. */
class StrictDecodingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final UUID P = Samples.PAYMENT;
    private final MessageCodec codec = new MessageCodec();

    private String reserveJson() {
        return codec.encode(Envelope.of(new ReserveFunds(P, "alice", 100, "INR"), 1, CLOCK));
    }

    private void assertPoison(String json) {
        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    // ---- 1. strict decoding

    @Test
    void REQ_PF_020_amount_as_a_decimal_is_a_poison_message() {
        assertPoison(reserveJson().replaceFirst("\"amount\"\\s*:\\s*100", "\"amount\":100.99"));
    }

    @Test
    void REQ_PF_020_amount_as_a_string_is_a_poison_message() {
        assertPoison(reserveJson().replaceFirst("\"amount\"\\s*:\\s*100", "\"amount\":\"100\""));
    }

    @Test
    void REQ_PF_020_text_field_given_as_a_number_is_a_poison_message() {
        assertPoison(reserveJson().replaceFirst("\"payer\"\\s*:\\s*\"alice\"", "\"payer\":12345"));
    }

    @Test
    void REQ_PF_020_duplicate_keys_are_a_poison_message_not_last_one_wins() {
        assertPoison(reserveJson().replaceFirst("\"amount\"\\s*:\\s*100", "\"amount\":1,\"amount\":999999"));
    }

    @Test
    void REQ_PF_020_json_nested_deeper_than_16_levels_in_an_unknown_payload_field_is_a_poison_message() {
        String nested = "{\"a\":".repeat(20) + "1" + "}".repeat(20);
        assertPoison(reserveJson().replaceFirst("\"payload\"\\s*:\\s*\\{", "\"payload\":{\"extra\":" + nested + ","));
    }

    @Test
    void REQ_PF_020_document_larger_than_64_kib_is_a_poison_message() {
        String padding = "x".repeat(70 * 1024);
        assertPoison(
                reserveJson().replaceFirst("\"payload\"\\s*:\\s*\\{", "\"payload\":{\"extra\":\"" + padding + "\","));
    }

    // ---- 2. bounds on records

    @Test
    void REQ_PF_004_amount_above_max_is_rejected_and_exactly_max_is_accepted() {
        assertThat(Checks.MAX_AMOUNT).isEqualTo(10_000_000L);
        assertThat(new ReserveFunds(P, "alice", 10_000_000, "INR").amount()).isEqualTo(10_000_000L);
        assertIllegal(() -> new ReserveFunds(P, "alice", 10_000_001, "INR"));
        assertIllegal(() -> new CheckRisk(P, "alice", 10_000_001, "INR"));
        assertIllegal(() -> new CaptureFunds(P, "alice", "bob", 10_000_001, "INR"));
        assertIllegal(() -> new ReleaseFunds(P, "alice", 10_000_001));
    }

    @Test
    void REQ_PF_004_amount_above_max_on_the_wire_is_a_poison_message() {
        assertPoison(reserveJson().replaceFirst("\"amount\"\\s*:\\s*100", "\"amount\":10000001"));
        assertPoison(reserveJson().replaceFirst("\"amount\"\\s*:\\s*100", "\"amount\":9223372036854775807"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "inr", "USD", "INR ", " INR", ""})
    void REQ_PF_004_currency_must_be_exactly_inr(String currency) {
        assertIllegal(() -> new ReserveFunds(P, "alice", 100, currency));
        assertIllegal(() -> new CheckRisk(P, "alice", 100, currency));
        assertIllegal(() -> new CaptureFunds(P, "alice", "bob", 100, currency));
        assertPoison(reserveJson().replace("\"INR\"", "\"" + currency + "\""));
    }

    @Test
    void REQ_PF_004_text_longer_than_64_chars_is_rejected_at_64_accepted() {
        String ok = "a".repeat(64);
        String tooLong = "a".repeat(65);
        assertThat(new ReserveFunds(P, ok, 1, "INR").payer()).isEqualTo(ok);
        assertThat(new FundsRejected(P, ok).reason()).isEqualTo(ok);
        assertThat(new RiskRejected(P, ok).rule()).isEqualTo(ok);

        assertIllegal(() -> new ReserveFunds(P, tooLong, 1, "INR"));
        assertIllegal(() -> new CheckRisk(P, tooLong, 1, "INR"));
        assertIllegal(() -> new CaptureFunds(P, tooLong, "bob", 1, "INR"));
        assertIllegal(() -> new CaptureFunds(P, "alice", tooLong, 1, "INR"));
        assertIllegal(() -> new ReleaseFunds(P, tooLong, 1));
        assertIllegal(() -> new FundsRejected(P, tooLong));
        assertIllegal(() -> new CaptureFailed(P, tooLong));
        assertIllegal(() -> new RiskRejected(P, tooLong));
        assertPoison(reserveJson().replace("\"alice\"", "\"" + tooLong + "\""));
    }

    @Test
    void REQ_PF_004_blank_text_is_rejected() {
        assertIllegal(() -> new FundsRejected(P, " "));
        assertIllegal(() -> new CaptureFailed(P, ""));
        assertIllegal(() -> new RiskRejected(P, "  "));
        assertIllegal(() -> new ReserveFunds(P, " ", 1, "INR"));
        assertPoison(reserveJson().replace("\"alice\"", "\" \""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"al ice", "alice\\n", "a/b", "al;ce", "älice", "a\\\"b", "<x>"})
    void REQ_PF_004_payer_and_payee_must_match_the_account_id_pattern(String bad) {
        // the argument is JSON-escaped text; the record check sees the unescaped value
        String raw = bad.replace("\\n", "\n").replace("\\\"", "\"");
        assertIllegal(() -> new ReserveFunds(P, raw, 1, "INR"));
        assertIllegal(() -> new CaptureFunds(P, "alice", raw, 1, "INR"));
        assertPoison(reserveJson().replace("\"alice\"", "\"" + bad + "\""));
    }

    @Test
    void REQ_PF_004_payer_and_payee_allow_letters_digits_dot_underscore_at_and_dash() {
        String id = "Ab0._@-z";
        CaptureFunds c = new CaptureFunds(P, id, id, 1, "INR");
        assertThat(codec.decode(codec.encode(Envelope.of(c, 1, CLOCK))).payload())
                .isEqualTo(c);
    }

    // ---- 3. log-safe errors

    @Test
    void REQ_PF_020_error_for_unknown_type_never_echoes_the_value_or_a_newline() {
        String json = codec.encode(Envelope.of(new FundsReserved(P), 1, CLOCK))
                .replace("\"FundsReserved\"", "\"Evil\\nINFO forged line\"");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(PoisonMessageException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("\n")
                        .doesNotContain("\r")
                        .doesNotContain("forged")
                        .doesNotContain("Evil"));
    }

    @Test
    void REQ_PF_020_error_for_bad_amount_names_the_field_but_not_the_values() {
        String json = codec.encode(Envelope.of(new ReserveFunds(P, "SECRET-VALUE-abc", 100, "INR"), 1, CLOCK))
                .replaceFirst("\"amount\"\\s*:\\s*100", "\"amount\":98765432");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(PoisonMessageException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("amount")
                        .doesNotContain("SECRET-VALUE")
                        .doesNotContain("98765432"));
    }

    @Test
    void REQ_PF_020_error_for_malformed_json_does_not_echo_the_document() {
        assertThatThrownBy(() -> codec.decode("{\"payer\":\"SECRET-VALUE-abc\" oops"))
                .isInstanceOf(PoisonMessageException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET-VALUE"));
    }

    private static void assertIllegal(Supplier<Object> construct) {
        assertThatThrownBy(construct::get).isInstanceOf(IllegalArgumentException.class);
    }
}
