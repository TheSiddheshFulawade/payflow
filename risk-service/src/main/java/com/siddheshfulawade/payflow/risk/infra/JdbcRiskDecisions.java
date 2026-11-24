package com.siddheshfulawade.payflow.risk.infra;

import com.siddheshfulawade.payflow.risk.app.RiskDecisions;
import com.siddheshfulawade.payflow.risk.domain.RiskDecision;
import com.siddheshfulawade.payflow.risk.domain.RiskRule;
import com.siddheshfulawade.payflow.risk.domain.Verdict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRiskDecisions implements RiskDecisions {

    private final JdbcClient jdbc;

    JdbcRiskDecisions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(UUID paymentId, String payer, long amount) {
        // A concurrent claim of the same payment blocks on the primary key until the first transaction ends: if it
        // committed, "do nothing" (0 rows); if it rolled back, this insert goes ahead.
        return jdbc.sql("""
                        insert into risk_decision(payment_id, payer, amount, decision)
                        values (:payment, :payer, :amount, 'PENDING')
                        on conflict (payment_id) do nothing""")
                        .param("payment", paymentId)
                        .param("payer", payer)
                        .param("amount", amount)
                        .update()
                == 1;
    }

    @Override
    public Optional<RiskDecision> find(UUID paymentId) {
        // no lock needed: a committed decision never changes again
        return jdbc.sql("select payer, amount, decision, rule from risk_decision where payment_id = :payment")
                .param("payment", paymentId)
                .query(JdbcRiskDecisions::toDecision)
                .optional();
    }

    @Override
    public void approve(UUID paymentId) {
        decide(paymentId, Verdict.APPROVED, null);
    }

    @Override
    public void reject(UUID paymentId, RiskRule rule) {
        decide(paymentId, Verdict.REJECTED, rule);
    }

    private void decide(UUID paymentId, Verdict verdict, RiskRule rule) {
        // the only update of a decision, and only out of PENDING: a decision is final
        int updated = jdbc.sql("""
                        update risk_decision set decision = :decision, rule = :rule, decided_at = now()
                        where payment_id = :payment and decision = 'PENDING'""")
                .param("decision", verdict.name())
                .param("rule", rule == null ? null : rule.name())
                .param("payment", paymentId)
                .update();
        Updates.exactlyOne(updated, "decision of payment " + paymentId + " to " + verdict);
    }

    private static RiskDecision toDecision(ResultSet rs, int row) throws SQLException {
        String rule = rs.getString("rule");
        return new RiskDecision(
                rs.getString("payer"),
                rs.getLong("amount"),
                Verdict.valueOf(rs.getString("decision")),
                rule == null ? null : RiskRule.valueOf(rule));
    }
}
