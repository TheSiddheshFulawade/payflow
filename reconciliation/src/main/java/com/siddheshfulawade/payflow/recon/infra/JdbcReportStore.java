package com.siddheshfulawade.payflow.recon.infra;

import com.siddheshfulawade.payflow.recon.app.ReportStore;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReportStore implements ReportStore {

    private final JdbcTemplate jdbc;

    JdbcReportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Invariant (REQ-PF-041): one report row per (business date, payment id). The primary key refuses a second row and
     * "on conflict do nothing" turns that into a no-op, so a restart after a crash in the middle of the report adds
     * the missing rows and never a duplicate. The first row written wins: a rerun does not rewrite history.
     */
    @Override
    public int insertIgnoringDuplicates(LocalDate businessDate, List<ReconResult> chunk) {
        int[][] counts = jdbc.batchUpdate("""
                        insert into recon_result(business_date, payment_id, outcome)
                        values (?, ?, ?)
                        on conflict (business_date, payment_id) do nothing""", chunk, chunk.size(), (ps, result) -> {
            ps.setObject(1, businessDate);
            ps.setObject(2, result.paymentId());
            ps.setString(3, result.outcome().name());
        });
        int inserted = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                // the driver may answer "success, count unknown" for a batched statement: count it as new
                if (count == 1 || count == Statement.SUCCESS_NO_INFO) {
                    inserted++;
                }
            }
        }
        return inserted;
    }
}
