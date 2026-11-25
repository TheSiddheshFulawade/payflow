package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Session settings of the app's own connections. A frozen instance whose relay transaction holds outbox rows would keep
 * those payments postponed (the sweeper waits for an unsent command) until PostgreSQL drops the dead session, so the
 * database must do that itself, after more than the relay's waits: 10 s per topic for metadata plus 35 s for acks.
 */
class ConnectionTimeoutsTest extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    @Autowired
    private DataSource dataSource;

    private static String show(Connection connection, String setting) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("show " + setting)) {
            result.next();
            return result.getString(1);
        }
    }

    @Test
    @DisplayName(
            "REQ-PF-020 a connection of the app's pool has idle_in_transaction_session_timeout 60 s and lock_timeout 5 s")
    void poolConnectionsCarryBothTimeouts() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(show(connection, "idle_in_transaction_session_timeout")).isIn("1min", "60s", "60000ms");
            assertThat(show(connection, "lock_timeout")).isIn("5s", "5000ms");
        }
    }

    @Test
    @DisplayName(
            "REQ-PF-020 a transaction left idle loses its row locks when the timeout passes, and the sweep proceeds")
    void idleTransactionLosesItsLocksAndTheSweepProceeds() throws Exception {
        clock.set(T0);
        Payment p = newPayment();
        relayAll();
        clock.advance(Duration.ofSeconds(11));
        Connection frozen = dataSource.getConnection();
        try {
            frozen.setAutoCommit(false);
            try (Statement statement = frozen.createStatement()) {
                // the same setting as the pool's, only shorter, so the test does not wait a minute
                statement.execute("set local idle_in_transaction_session_timeout = '1s'");
                statement.execute("select id from payment where id = '" + p.id() + "' for update");
            }

            // not touched again: the frozen "instance" sits idle in its transaction
            await().atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        sweeper.sweep();
                        assertThat(stepAttempts(p.id()))
                                .as("the sweep skipped the locked row until the database ended the session")
                                .isEqualTo(2);
                    });
        } finally {
            try {
                frozen.close();
            } catch (SQLException expected) {
                // the server already ended this session
            }
        }
    }
}
