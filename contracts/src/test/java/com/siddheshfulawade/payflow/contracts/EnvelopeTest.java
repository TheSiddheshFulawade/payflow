package com.siddheshfulawade.payflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvelopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final Message PAYLOAD = new FundsReserved(Samples.PAYMENT);

    @Test
    void REQ_PF_020_of_fills_type_version_time_and_a_fresh_message_id() {
        Envelope a = Envelope.of(PAYLOAD, 2, CLOCK);
        Envelope b = Envelope.of(PAYLOAD, 2, CLOCK);

        assertThat(a.type()).isEqualTo("FundsReserved");
        assertThat(a.version()).isEqualTo(1);
        assertThat(a.attempt()).isEqualTo(2);
        assertThat(a.paymentId()).isEqualTo(Samples.PAYMENT);
        assertThat(a.occurredAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(a.messageId()).isNotNull().isNotEqualTo(b.messageId());
    }

    @Test
    void REQ_PF_020_attempt_below_one_is_rejected() {
        assertThatThrownBy(() -> Envelope.of(PAYLOAD, 0, CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void REQ_PF_020_version_below_one_is_rejected() {
        assertThatThrownBy(() ->
                        new Envelope(UUID.randomUUID(), Samples.PAYMENT, "FundsReserved", 0, 1, Instant.now(), PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void REQ_PF_020_payment_id_must_equal_the_payload_payment_id() {
        assertThatThrownBy(() -> new Envelope(
                        UUID.randomUUID(), UUID.randomUUID(), "FundsReserved", 1, 1, Instant.now(), PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void REQ_PF_020_null_fields_are_rejected() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> new Envelope(null, Samples.PAYMENT, "FundsReserved", 1, 1, now, PAYLOAD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Envelope(id, null, "FundsReserved", 1, 1, now, PAYLOAD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Envelope(id, Samples.PAYMENT, null, 1, 1, now, PAYLOAD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Envelope(id, Samples.PAYMENT, "FundsReserved", 1, 1, null, PAYLOAD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Envelope(id, Samples.PAYMENT, "FundsReserved", 1, 1, now, null))
                .isInstanceOf(NullPointerException.class);
    }
}
