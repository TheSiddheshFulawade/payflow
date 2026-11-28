package com.siddheshfulawade.payflow.wallet.infra;

import com.siddheshfulawade.payflow.wallet.app.StatementLine;
import com.siddheshfulawade.payflow.wallet.app.Statements;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStatements implements Statements {

    private final JdbcClient jdbc;

    JdbcStatements(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Invariant (REQ-PF-040): a hold is the permanent record of one payment's decision and a settled hold (CAPTURED,
     * RELEASED, REJECTED) never changes again, so its {@code updated_at} puts it on exactly one UTC day. Money moved is
     * the hold's amount when CAPTURED and 0 otherwise; the hold row is read, not the ledger, so there is one line per
     * payment by construction (hold primary key). SEED ledger entries have no hold and never appear.
     */
    @Override
    public List<StatementLine> forDate(LocalDate businessDate) {
        OffsetDateTime from = businessDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                        select payment_id, state, case when state = 'CAPTURED' then amount else 0 end as captured
                        from hold
                        where state in ('CAPTURED', 'RELEASED', 'REJECTED')
                          and updated_at >= :from and updated_at < :to
                        order by payment_id""")
                .param("from", from)
                .param("to", from.plusDays(1))
                .query((rs, row) -> new StatementLine(
                        rs.getObject("payment_id", UUID.class), rs.getLong("captured"), rs.getString("state")))
                .list();
    }
}
