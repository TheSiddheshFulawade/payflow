package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.Payments;
import com.siddheshfulawade.payflow.payment.app.StaleVersionException;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentEvent;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPayments implements Payments {

    private static final String SELECT = """
            select id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,
                   state, step_attempts, deadline_at, version, created_at, updated_at
            from payment
            """;

    private final JdbcClient jdbc;

    JdbcPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Payment payment) {
        // A concurrent insert of the same key blocks on the unique constraint until the first transaction ends: if it
        // committed, "do nothing" (0 rows); if it rolled back, this insert goes ahead.
        return jdbc.sql("""
                        insert into payment(id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,
                                            state, step_attempts, deadline_at, version, created_at, updated_at)
                        values (:id, :client, :key, :hash, :payer, :payee, :amount, :currency,
                                :state, :attempts, :deadline, :version, :created, :updated)
                        on conflict (client_id, idempotency_key) do nothing""")
                        .param("id", payment.id())
                        .param("client", payment.clientId())
                        .param("key", payment.idempotencyKey())
                        .param("hash", payment.requestHash())
                        .param("payer", payment.payer())
                        .param("payee", payment.payee())
                        .param("amount", payment.amount())
                        .param("currency", payment.currency())
                        .param("state", payment.state().name())
                        .param("attempts", payment.stepAttempts())
                        .param("deadline", timestamp(payment.deadlineAt()))
                        .param("version", payment.version())
                        .param("created", timestamp(payment.createdAt()))
                        .param("updated", timestamp(payment.updatedAt()))
                        .update()
                == 1;
    }

    @Override
    public Optional<Payment> find(UUID id) {
        // no lock: the version check of the update decides who may move the payment
        return jdbc.sql(SELECT + "where id = :id")
                .param("id", id)
                .query(JdbcPayments::toPayment)
                .optional();
    }

    @Override
    public Optional<Payment> findByKey(String clientId, String idempotencyKey) {
        return jdbc.sql(SELECT + "where client_id = :client and idempotency_key = :key")
                .param("client", clientId)
                .param("key", idempotencyKey)
                .query(JdbcPayments::toPayment)
                .optional();
    }

    @Override
    public void update(Payment from, Payment to) {
        // The only update of a payment. A concurrent one makes this wait on the row and then match nothing, so no two
        // transactions ever advance the payment from the same version (REQ-PF-014).
        int updated = jdbc.sql("""
                        update payment set state = :state, step_attempts = :attempts, deadline_at = :deadline,
                                           version = version + 1, updated_at = :updated
                        where id = :id and version = :version""")
                .param("state", to.state().name())
                .param("attempts", to.stepAttempts())
                .param("deadline", timestamp(to.deadlineAt()))
                .param("updated", timestamp(to.updatedAt()))
                .param("id", from.id())
                .param("version", from.version())
                .update();
        if (updated != 1) {
            throw new StaleVersionException(from.id());
        }
    }

    @Override
    public void addEvent(PaymentEvent event) {
        jdbc.sql("""
                        insert into payment_event(payment_id, from_state, to_state, message_id, attempt, reason, created_at)
                        values (:payment, :from, :to, :message, :attempt, :reason, :created)""")
                .param("payment", event.paymentId())
                .param("from", event.from() == null ? null : event.from().name())
                .param("to", event.to().name())
                .param("message", event.messageId())
                .param("attempt", event.attempt())
                .param("reason", event.reason())
                .param("created", timestamp(event.createdAt()))
                .update();
    }

    @Override
    public List<UUID> overdueIds(Instant now, int limit) {
        return jdbc.sql("select id from payment where deadline_at < :now order by deadline_at limit :limit")
                .param("now", timestamp(now))
                .param("limit", limit)
                .query((rs, row) -> rs.getObject("id", UUID.class))
                .list();
    }

    @Override
    public Optional<Payment> lockOverdue(UUID id, Instant now) {
        // Deadline and state are checked again under the lock: a reply or another sweeper may have moved the payment
        // since overdueIds. Skip locked: whoever holds the row is moving it right now.
        return jdbc.sql(SELECT + """
                        where id = :id and deadline_at < :now
                          and state in ('RESERVING', 'RISK_CHECKING', 'CAPTURING', 'RELEASING')
                        for update skip locked""")
                .param("id", id)
                .param("now", timestamp(now))
                .query(JdbcPayments::toPayment)
                .optional();
    }

    private static Payment toPayment(ResultSet rs, int row) throws SQLException {
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getString("client_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("payer"),
                rs.getString("payee"),
                rs.getLong("amount"),
                rs.getString("currency"),
                PaymentState.valueOf(rs.getString("state")),
                rs.getInt("step_attempts"),
                instant(rs, "deadline_at"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    /** The JDBC type for timestamptz is {@code OffsetDateTime}, not {@code Instant}. */
    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
