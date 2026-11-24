package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

/** The database is the last line of defence: it refuses rows the code must never produce. */
class PaymentSchemaTest extends PaymentIntegrationTest {

    private static final String ACTIVE_DEADLINE = "now() + interval '10 seconds'";

    /** A valid RESERVING payment unless a parameter says otherwise. */
    private void insert(
            String clientId,
            String key,
            String payer,
            String payee,
            long amount,
            String currency,
            String state,
            int stepAttempts,
            String deadlineSql,
            long version) {
        jdbc.update(
                "insert into payment(id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,"
                        + " state, step_attempts, deadline_at, version, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + deadlineSql + ", ?, now(), now())",
                UUID.randomUUID(),
                clientId,
                key,
                "0".repeat(64),
                payer,
                payee,
                amount,
                currency,
                state,
                stepAttempts,
                version);
    }

    private void insertValid() {
        insert("local", freshKey(), "alice", "bob", 500, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);
    }

    private void assertRefused(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentRows()).as("nothing stored").isZero();
    }

    @Test
    @DisplayName("REQ-PF-001 the baseline row of these tests is valid (so each refusal below has one cause)")
    void baselineIsValid() {
        insertValid();

        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-004 amounts 1 and 10,000,000 are stored; 0, -1 and 10,000,001 are refused")
    void amountBounds() {
        insert("local", freshKey(), "alice", "bob", 1, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);
        insert("local", freshKey(), "alice", "bob", 10_000_000, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);
        assertThat(paymentRows()).isEqualTo(2);

        for (long bad : new long[] {0, -1, 10_000_001}) {
            assertThatThrownBy(() ->
                            insert("local", freshKey(), "alice", "bob", bad, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0))
                    .as("amount %d", bad)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(paymentRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-004 the database refuses a currency other than INR")
    void currencyCheck() {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "USD", "RESERVING", 1, ACTIVE_DEADLINE, 0));
    }

    @Test
    @DisplayName("REQ-PF-004 the database refuses a payment to oneself")
    void payerAndPayeeDiffer() {
        assertRefused(
                () -> insert("local", freshKey(), "alice", "alice", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0));
    }

    @Test
    @DisplayName("REQ-PF-010 the database refuses an unknown state")
    void stateCheck() {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", "BOGUS", 1, ACTIVE_DEADLINE, 0));
    }

    @Test
    @DisplayName("REQ-PF-010 the database refuses negative step attempts and a negative version")
    void counterChecks() {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", "RESERVING", -1, ACTIVE_DEADLINE, 0));
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, -1));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RESERVING", "RISK_CHECKING", "CAPTURING", "RELEASING"})
    @DisplayName("REQ-PF-012 an in-flight payment must have a deadline, or the sweeper would never see it")
    void inFlightNeedsADeadline(PaymentState state) {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 1, "null", 0));
        insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 1, ACTIVE_DEADLINE, 0);

        assertThat(paymentRows()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RECEIVED", "COMPLETED", "REFUNDED", "REJECTED"})
    @DisplayName("REQ-PF-012 RECEIVED and terminal payments must have no deadline")
    void idleAndTerminalHaveNoDeadline(PaymentState state) {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 0, ACTIVE_DEADLINE, 0));
        insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 0, "null", 0);

        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-002 one client cannot have the same Idempotency-Key twice, two clients can")
    void keyIsUniquePerClient() {
        String key = freshKey();
        insert("local", key, "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);

        assertThatThrownBy(() -> insert("local", key, "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert("other", key, "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);

        assertThat(paymentRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-004 a refused row's values are not in the exception (logServerErrorDetail=false)")
    void errorsDoNotEchoRowValues() {
        assertThatThrownBy(() -> insert(
                        "local",
                        freshKey(),
                        "leaky-payer-77",
                        "leaky-payee-77",
                        0,
                        "INR",
                        "RESERVING",
                        1,
                        ACTIVE_DEADLINE,
                        0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .doesNotContain("leaky-payer-77")
                .doesNotContain("leaky-payee-77");
    }
}
