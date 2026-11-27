package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.payment.app.StatementLine;
import com.siddheshfulawade.payflow.payment.app.Statements;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** REQ-PF-040: the payment statement query (Testcontainers PostgreSQL; tag "it" comes from the base class). */
class PaymentStatementQueryIT extends PaymentIntegrationTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-26");

    @Autowired
    Statements statements;

    private UUID insert(String id, String state, long amount, String updatedAtUtc) {
        UUID payment = UUID.fromString(id);
        String deadline = state.equals("RESERVING") ? "now() + interval '1 hour'" : "null";
        jdbc.update(
                "insert into payment(id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,"
                        + " state, step_attempts, deadline_at, version, created_at, updated_at)"
                        + " values (?, 'c', ?, ?, 'alice', 'bob', ?, 'INR', ?, 0, " + deadline
                        + ", 0, ?::timestamptz, ?::timestamptz)",
                payment,
                "k-" + id,
                "0".repeat(64),
                amount,
                state,
                updatedAtUtc,
                updatedAtUtc);
        return payment;
    }

    @Test
    @DisplayName("REQ-PF-040 the statement lists terminal payments of the UTC date, ordered by payment id")
    void terminalPaymentsOfTheDay() {
        insert("00000000-0000-0000-0000-000000000003", "REJECTED", 30, "2026-11-26 10:00:00+00");
        insert("00000000-0000-0000-0000-000000000001", "COMPLETED", 10_000_000, "2026-11-26 00:00:00+00");
        insert("00000000-0000-0000-0000-000000000002", "REVERSED", 20, "2026-11-26 23:59:59.999+00");

        List<StatementLine> lines = statements.forDate(DAY);

        assertThat(lines)
                .containsExactly(
                        new StatementLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                10_000_000,
                                "INR",
                                "COMPLETED"),
                        new StatementLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000002"), 20, "INR", "REVERSED"),
                        new StatementLine(
                                UUID.fromString("00000000-0000-0000-0000-000000000003"), 30, "INR", "REJECTED"));
    }

    @Test
    @DisplayName("REQ-PF-040 in-flight payments and payments of other dates are not on the statement")
    void otherDatesAndInFlight() {
        insert("00000000-0000-0000-0000-000000000001", "RESERVING", 10, "2026-11-26 10:00:00+00");
        insert("00000000-0000-0000-0000-000000000002", "COMPLETED", 10, "2026-11-25 23:59:59.999+00");
        insert("00000000-0000-0000-0000-000000000003", "COMPLETED", 10, "2026-11-27 00:00:00+00");

        assertThat(statements.forDate(DAY)).isEmpty();
    }
}
