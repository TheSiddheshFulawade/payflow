package com.siddheshfulawade.payflow.wallet.infra;

import com.siddheshfulawade.payflow.wallet.app.Holds;
import com.siddheshfulawade.payflow.wallet.domain.Hold;
import com.siddheshfulawade.payflow.wallet.domain.HoldState;
import com.siddheshfulawade.payflow.wallet.domain.RejectReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHolds implements Holds {

    private final JdbcClient jdbc;

    JdbcHolds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(UUID paymentId, String payer, long amount, HoldState state) {
        // A concurrent insert of the same payment blocks on the primary key until the first transaction ends: if it
        // committed, "do nothing" (0 rows); if it rolled back, this insert goes ahead.
        return jdbc.sql("""
                        insert into hold(payment_id, payer, amount, state)
                        values (:payment, :payer, :amount, :state)
                        on conflict (payment_id) do nothing""")
                        .param("payment", paymentId)
                        .param("payer", payer)
                        .param("amount", amount)
                        .param("state", state.name())
                        .update()
                == 1;
    }

    @Override
    public Optional<Hold> lock(UUID paymentId) {
        // for update: waits for the transaction that is changing the hold, then reads its committed state
        return jdbc.sql("""
                        select payment_id, payer, amount, state, reason, payee
                        from hold where payment_id = :payment for update""")
                .param("payment", paymentId)
                .query(JdbcHolds::toHold)
                .optional();
    }

    @Override
    public void reject(UUID paymentId, RejectReason reason) {
        finish(paymentId, HoldState.REJECTED, reason, null);
    }

    @Override
    public void release(UUID paymentId) {
        finish(paymentId, HoldState.RELEASED, null, null);
    }

    @Override
    public void capture(UUID paymentId, String payee) {
        finish(paymentId, HoldState.CAPTURED, null, payee);
    }

    private void finish(UUID paymentId, HoldState state, RejectReason reason, String payee) {
        // The only update of a hold, and only out of RESERVED: a released hold can never be captured, a captured one
        // never released, and no hold ever becomes RESERVED again.
        int updated = jdbc.sql("""
                        update hold set state = :state, reason = :reason, payee = :payee, updated_at = now()
                        where payment_id = :payment and state = 'RESERVED'""")
                .param("state", state.name())
                .param("reason", reason == null ? null : reason.name())
                .param("payee", payee)
                .param("payment", paymentId)
                .update();
        Updates.exactlyOne(updated, "hold of payment " + paymentId + " to " + state);
    }

    private static Hold toHold(ResultSet rs, int row) throws SQLException {
        String reason = rs.getString("reason");
        return new Hold(
                rs.getObject("payment_id", UUID.class),
                rs.getString("payer"),
                rs.getLong("amount"),
                HoldState.valueOf(rs.getString("state")),
                reason == null ? null : RejectReason.valueOf(reason),
                rs.getString("payee"));
    }
}
