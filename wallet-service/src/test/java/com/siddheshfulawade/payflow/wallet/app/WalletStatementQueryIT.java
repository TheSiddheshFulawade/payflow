package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** REQ-PF-040: the wallet statement query (Testcontainers PostgreSQL; tag "it" comes from the base class). */
class WalletStatementQueryIT extends WalletIntegrationTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-26");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    Statements statements;

    @BeforeEach
    void wallets() {
        jdbc.update("insert into wallet(id, owner_sub, available, held, status) values ('alice','o',0,0,'ACTIVE')");
        jdbc.update("insert into wallet(id, owner_sub, available, held, status) values ('bob','o',0,0,'ACTIVE')");
    }

    private void hold(UUID payment, long amount, String state, String at) {
        String reason = state.equals("REJECTED") ? "'INSUFFICIENT_FUNDS'" : "null";
        String payee = state.equals("CAPTURED") ? "'bob'" : "null";
        jdbc.update(
                "insert into hold(payment_id, payer, amount, state, reason, payee, updated_at)"
                        + " values (?, 'alice', ?, ?, " + reason + ", " + payee + ", ?::timestamptz)",
                payment,
                amount,
                state,
                at);
    }

    private void ledger(UUID payment, String kind, String wallet, long amount, String at) {
        jdbc.update(
                "insert into ledger_entry(id, wallet_id, payment_id, kind, amount, created_at)"
                        + " values (?, ?, ?, ?, ?, ?::timestamptz)",
                UUID.randomUUID(),
                wallet,
                payment,
                kind,
                amount,
                at);
    }

    @Test
    @DisplayName("REQ-PF-040 one line per payment: CAPTURED with the debited amount, RELEASED and REJECTED with 0")
    void oneLinePerPayment() {
        String at = "2026-11-26 12:00:00+00";
        hold(P3, 30, "REJECTED", at);
        hold(P2, 20, "RELEASED", at);
        ledger(P2, "RESERVE", "alice", 20, at);
        ledger(P2, "RELEASE", "alice", 20, at);
        hold(P1, 10, "CAPTURED", at);
        ledger(P1, "RESERVE", "alice", 10, at);
        ledger(P1, "CAPTURE_DEBIT", "alice", 10, at);
        ledger(P1, "CAPTURE_CREDIT", "bob", 10, at);

        assertThat(statements.forDate(DAY))
                .containsExactly(
                        new StatementLine(P1, 10, "CAPTURED"),
                        new StatementLine(P2, 0, "RELEASED"),
                        new StatementLine(P3, 0, "REJECTED"));
    }

    @Test
    @DisplayName("REQ-PF-040 a payment still RESERVED, SEED entries and other UTC dates are not on the statement")
    void notSettledOrOtherDate() {
        hold(P1, 10, "RESERVED", "2026-11-26 12:00:00+00");
        hold(P2, 10, "RELEASED", "2026-11-25 23:59:59.999+00");
        hold(P3, 10, "REJECTED", "2026-11-27 00:00:00+00");
        jdbc.update(
                "insert into ledger_entry(id, wallet_id, kind, amount, created_at)"
                        + " values (?, 'alice', 'SEED', 100, '2026-11-26 09:00:00+00')",
                UUID.randomUUID());

        assertThat(statements.forDate(DAY)).isEmpty();
    }
}
