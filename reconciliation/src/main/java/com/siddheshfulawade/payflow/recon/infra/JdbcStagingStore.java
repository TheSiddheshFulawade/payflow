package com.siddheshfulawade.payflow.recon.infra;

import com.siddheshfulawade.payflow.recon.app.StagingStore;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The staging tables. The writes join the transaction of the Batch chunk that calls them (same DataSource), so a chunk
 * is staged completely or not at all.
 */
@Repository
class JdbcStagingStore implements StagingStore {

    private final JdbcTemplate jdbc;

    JdbcStagingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void stagePayments(LocalDate businessDate, List<PaymentLine> chunk) {
        // Invariant (REQ-PF-041): "on conflict do update" makes staging a line twice leave one row (a rerun).
        jdbc.batchUpdate("""
                        insert into recon_staging_payment(business_date, payment_id, amount, currency, state)
                        values (?, ?, ?, ?, ?)
                        on conflict (business_date, payment_id)
                        do update set amount = excluded.amount, currency = excluded.currency, state = excluded.state""", chunk, chunk.size(), (ps, line) -> {
            ps.setObject(1, businessDate);
            ps.setObject(2, line.paymentId());
            ps.setLong(3, line.amount());
            ps.setString(4, line.currency());
            ps.setString(5, line.state());
        });
    }

    @Override
    public void stageWallets(LocalDate businessDate, List<WalletLine> chunk) {
        jdbc.batchUpdate("""
                        insert into recon_staging_wallet(business_date, payment_id, captured_amount, status)
                        values (?, ?, ?, ?)
                        on conflict (business_date, payment_id)
                        do update set captured_amount = excluded.captured_amount, status = excluded.status""", chunk, chunk.size(), (ps, line) -> {
            ps.setObject(1, businessDate);
            ps.setObject(2, line.paymentId());
            ps.setLong(3, line.capturedAmount());
            ps.setString(4, line.status());
        });
    }

    @Override
    public List<PaymentLine> stagedPayments(LocalDate businessDate) {
        return jdbc.query(
                """
                        select payment_id, amount, currency, state from recon_staging_payment
                        where business_date = ? order by payment_id""",
                (rs, row) -> new PaymentLine(
                        rs.getObject("payment_id", UUID.class),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getString("state")),
                businessDate);
    }

    @Override
    public List<WalletLine> stagedWallets(LocalDate businessDate) {
        return jdbc.query(
                """
                        select payment_id, captured_amount, status from recon_staging_wallet
                        where business_date = ? order by payment_id""",
                (rs, row) -> new WalletLine(
                        rs.getObject("payment_id", UUID.class), rs.getLong("captured_amount"), rs.getString("status")),
                businessDate);
    }
}
