package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** The database is the last line of defence: it refuses states the code must never produce. */
class WalletSchemaTest extends WalletIntegrationTest {

    @Test
    @DisplayName("REQ-PF-030 the database refuses a negative available balance")
    void availableCheck() {
        wallet("alice", 100);

        assertThatThrownBy(() -> jdbc.update("update wallet set available = -1 where id = 'alice'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertBalances("alice", 100, 0);
    }

    @Test
    @DisplayName("REQ-PF-030 the database refuses a negative held balance")
    void heldCheck() {
        wallet("alice", 100);

        assertThatThrownBy(() -> jdbc.update("update wallet set held = -1 where id = 'alice'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("REQ-PF-030 the database refuses an unknown wallet status")
    void statusCheck() {
        wallet("alice", 100);

        assertThatThrownBy(() -> jdbc.update("update wallet set status = 'BOGUS' where id = 'alice'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("REQ-PF-030 the database refuses a hold of 0 and an unknown hold state")
    void holdChecks() {
        assertThatThrownBy(() -> jdbc.update(
                        "insert into hold(payment_id, payer, amount, state, updated_at) values (?, 'a', 0, 'RESERVED', now())",
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "insert into hold(payment_id, payer, amount, state, updated_at) values (?, 'a', 5, 'BOGUS', now())",
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("REQ-PF-030 a payment has at most one hold row (primary key)")
    void oneHoldPerPayment() {
        UUID payment = UUID.randomUUID();
        String sql =
                "insert into hold(payment_id, payer, amount, state, updated_at) values (?, 'a', 5, 'RESERVED', now())";
        jdbc.update(sql, payment);

        assertThatThrownBy(() -> jdbc.update(sql, payment)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(holdRows(payment)).isEqualTo(1);
    }
}
