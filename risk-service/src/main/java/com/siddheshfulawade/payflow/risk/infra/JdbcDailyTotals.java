package com.siddheshfulawade.payflow.risk.infra;

import com.siddheshfulawade.payflow.risk.app.DailyTotals;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDailyTotals implements DailyTotals {

    private final JdbcClient jdbc;

    JdbcDailyTotals(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lock(String payer, LocalDate date) {
        // the row must exist to be locked: without it, two first payments of the day would both see a total of 0
        jdbc.sql("""
                        insert into daily_total(payer, business_date, amount) values (:payer, :date, 0)
                        on conflict (payer, business_date) do nothing""").param("payer", payer).param("date", date).update();
        // for update: waits for the payment that is being decided, then reads its committed total
        return jdbc.sql("""
                        select amount from daily_total
                        where payer = :payer and business_date = :date for update""")
                .param("payer", payer)
                .param("date", date)
                .query(Long.class)
                .single();
    }

    @Override
    public void add(String payer, LocalDate date, long amount, long limit) {
        // Relative update, and the decision repeated in SQL: if the code were wrong, 0 rows change and it rolls back.
        int updated = jdbc.sql("""
                        update daily_total set amount = amount + :amount
                        where payer = :payer and business_date = :date and amount + :amount <= :limit""")
                .param("amount", amount)
                .param("payer", payer)
                .param("date", date)
                .param("limit", limit)
                .update();
        Updates.exactlyOne(updated, "daily total of " + date);
    }
}
