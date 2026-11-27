package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.StatementMatcher;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.LocalDate;
import java.util.List;

/**
 * One reconciliation of a business date: download, stage in chunks, match, write the report in chunks. The Spring Batch
 * job (ReconJobConfig) runs the same steps through the same methods, one Batch step each; {@link #run} is the plain
 * sequence, for tests and the measured run.
 *
 * <p>Invariant (REQ-PF-041): every write is idempotent on (business date, payment id) - staging replaces the row, the
 * report ignores a row that exists - so a run that crashed anywhere can simply be run again with the same date: no
 * gap and no duplicate. The report is written only after both statements are fully staged, and a statement with a
 * repeated payment id stops the run before anything is staged.
 */
public final class ReconciliationRun {

    private final StatementSource source;
    private final StagingStore staging;
    private final ReportStore report;

    public ReconciliationRun(StatementSource source, StagingStore staging, ReportStore report) {
        this.source = source;
        this.staging = staging;
        this.report = report;
    }

    public void run(LocalDate businessDate) {
        List<PaymentLine> payments = downloadPayments(businessDate);
        List<WalletLine> wallets = downloadWallets(businessDate);
        Chunks.partition(payments).forEach(chunk -> staging.stagePayments(businessDate, chunk));
        Chunks.partition(wallets).forEach(chunk -> staging.stageWallets(businessDate, chunk));
        Chunks.partition(matchStaged(businessDate))
                .forEach(chunk -> report.insertIgnoringDuplicates(businessDate, chunk));
    }

    /** Downloads the payment statement; refuses a repeated payment id or an unknown state before staging. */
    public List<PaymentLine> downloadPayments(LocalDate businessDate) {
        List<PaymentLine> lines = source.payments(businessDate);
        StatementMatcher.requireDistinctPayments(lines);
        return lines;
    }

    /** Downloads the wallet statement; refuses a repeated payment id or an unknown status before staging. */
    public List<WalletLine> downloadWallets(LocalDate businessDate) {
        List<WalletLine> lines = source.wallets(businessDate);
        StatementMatcher.requireDistinctWallets(lines);
        return lines;
    }

    /** Matches what is staged for the date, ordered by payment id. */
    public List<ReconResult> matchStaged(LocalDate businessDate) {
        return StatementMatcher.match(staging.stagedPayments(businessDate), staging.stagedWallets(businessDate));
    }
}
