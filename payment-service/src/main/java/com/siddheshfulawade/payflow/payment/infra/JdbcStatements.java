package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.StatementLine;
import com.siddheshfulawade.payflow.payment.app.Statements;
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
     * Invariant (REQ-PF-040): the business date is the UTC day of {@code updated_at}, and a terminal payment is never
     * updated again, so {@code updated_at} is the moment it became terminal and a payment is on exactly one day's
     * statement. The range is half open, so midnight belongs to the new day only.
     */
    @Override
    public List<StatementLine> forDate(LocalDate businessDate) {
        OffsetDateTime from = businessDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        return jdbc.sql("""
                        select id, amount, currency, state from payment
                        where state in ('COMPLETED', 'REVERSED', 'REJECTED')
                          and updated_at >= :from and updated_at < :to
                        order by id""")
                .param("from", from)
                .param("to", from.plusDays(1))
                .query((rs, row) -> new StatementLine(
                        rs.getObject("id", UUID.class),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getString("state")))
                .list();
    }
}
