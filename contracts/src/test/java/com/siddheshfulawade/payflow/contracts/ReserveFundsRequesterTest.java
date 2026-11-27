package com.siddheshfulawade.payflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ReserveFunds names who asked for the payment (the JWT subject of the creator), so the wallet can check that the payer
 * wallet belongs to that person (REQ-PF-003). The sender can never leave it out, and it is as bounded as the other text.
 */
class ReserveFundsRequesterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final UUID P = Samples.PAYMENT;
    private final MessageCodec codec = new MessageCodec();

    @Test
    void REQ_PF_003_requester_round_trips_through_json_under_its_own_name() {
        String sub = "7c1f3a52-9a53-4f55-8a0e-0d1c2b3a4e5f"; // what Keycloak uses as `sub`
        Envelope original = Envelope.of(new ReserveFunds(P, "alice", 100, "INR", sub), 1, CLOCK);

        String json = codec.encode(original);
        Envelope decoded = codec.decode(json);

        assertThat(json).containsPattern("\"requester\"\\s*:\\s*\"" + sub + "\"");
        assertThat(((ReserveFunds) decoded.payload()).requester()).isEqualTo(sub);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void REQ_PF_003_requester_is_kept_apart_from_the_payer() {
        ReserveFunds command = new ReserveFunds(P, "wallet-1", 100, "INR", "someone-else");

        assertThat(command.payer()).isEqualTo("wallet-1");
        assertThat(command.requester()).isEqualTo("someone-else");
    }

    @Test
    void REQ_PF_003_a_message_without_requester_is_a_poison_message_not_an_unchecked_reserve() {
        String json = codec.encode(Envelope.of(new ReserveFunds(P, "alice", 5, "INR", "sub-1"), 1, CLOCK))
                .replaceFirst(",?\\s*\"requester\"\\s*:\\s*\"sub-1\"", "");

        assertThat(json).doesNotContain("requester");
        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    @Test
    void REQ_PF_003_a_null_requester_in_json_is_a_poison_message() {
        String json = codec.encode(Envelope.of(new ReserveFunds(P, "alice", 5, "INR", "sub-1"), 1, CLOCK))
                .replace("\"requester\":\"sub-1\"", "\"requester\":null");

        assertThatThrownBy(() -> codec.decode(json)).isInstanceOf(PoisonMessageException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void REQ_PF_003_blank_or_missing_requester_is_rejected(String requester) {
        assertThatThrownBy(() -> new ReserveFunds(P, "alice", 1, "INR", requester))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    @Test
    void REQ_PF_003_requester_is_accepted_at_64_chars_and_rejected_at_65() {
        assertThat(new ReserveFunds(P, "alice", 1, "INR", "a".repeat(64)).requester())
                .hasSize(64);

        assertThatThrownBy(() -> new ReserveFunds(P, "alice", 1, "INR", "a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Stricter than "any text": the value reaches logs, so a line break must not get through. */
    @ParameterizedTest
    @ValueSource(strings = {"sub\n1", "sub\r1", "sub 1", "sub\u00001"})
    void REQ_PF_003_requester_with_whitespace_or_control_characters_is_rejected(String requester) {
        assertThatThrownBy(() -> new ReserveFunds(P, "alice", 1, "INR", requester))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** One subject rule: 1 to 64 printable ASCII characters (0x21 to 0x7E). Java's whitespace and control classes miss all of these. */
    @ParameterizedTest
    @ValueSource(strings = {"a\u0085b", "a\u2028b", "a\u2029b", "a\u202Eb", "a\u200Bb", "a\u00A0b", "\u00e9"})
    void REQ_PF_003_requester_outside_printable_ascii_is_rejected(String requester) {
        assertThatThrownBy(() -> new ReserveFunds(P, "alice", 1, "INR", requester))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void REQ_PF_003_requester_of_64_printable_ascii_chars_is_accepted_and_65_is_rejected() {
        StringBuilder all = new StringBuilder();
        for (char c = 0x21; c <= 0x7E; c++) {
            all.append(c);
        }
        String sub64 = all.substring(0, 64);

        assertThat(new ReserveFunds(P, "alice", 1, "INR", sub64).requester()).isEqualTo(sub64);
        assertThatThrownBy(() -> new ReserveFunds(P, "alice", 1, "INR", sub64 + "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void REQ_PF_003_error_for_a_bad_requester_names_the_field_but_not_the_value() {
        String json = codec.encode(Envelope.of(new ReserveFunds(P, "alice", 5, "INR", "sub-1"), 1, CLOCK))
                .replace("\"sub-1\"", "\"" + "SECRET-SUB-".repeat(8) + "\"");

        assertThatThrownBy(() -> codec.decode(json))
                .isInstanceOf(PoisonMessageException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("requester").doesNotContain("SECRET-SUB"));
    }
}
