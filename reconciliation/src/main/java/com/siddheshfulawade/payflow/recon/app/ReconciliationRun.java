package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.StatementMatcher;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One reconciliation of a business date: download, stage in chunks, match, write the report in chunks. The Spring Batch
 * job (ReconJobConfig) runs the same steps through the same methods, one Batch step each; {@link #run} is the plain
 * sequence, for tests and the measured run.
 *
 * <p>Invariant (REQ-PF-041): every write is idempotent on (business date, payment id) - staging replaces the row, the
 * report ignores a row that exists - so a run that crashed anywhere can simply be run again with the same date: no
 * gap and no duplicate. A fresh run clears the date's staging first, so a line that is no longer in the statements
 * cannot reach the report; a restart of a failed Batch job does not clear (it keeps its completed steps). The report
 * is written only after both statements are fully staged, and a statement with a repeated payment id stops the run
 * before anything is staged.
 *
 * <p>Invariant (REQ-PF-040): only a closed date is reconciled (business date at most today - 2, UTC), checked before
 * any download: a day still taking payments, or still being settled across midnight, has no final statement. A line in
 * a currency other than the configured one stops the run at the download, before it is staged.
 */
public final class ReconciliationRun {

    private final StatementSource source;
    private final StagingStore staging;
    private final ReportStore report;
    private final Clock clock;
    private final String currency;

    public ReconciliationRun(
            StatementSource source, StagingStore staging, ReportStore report, Clock clock, String currency) {
        this.source = source;
        this.staging = staging;
        this.report = report;
        this.clock = clock;
        this.currency = currency;
    }

    public void run(LocalDate businessDate) {
        requireClosed(businessDate);
        staging.clearStaging(businessDate);
        List<PaymentLine> payments = downloadPayments(businessDate);
        List<WalletLine> wallets = downloadWallets(businessDate);
        Chunks.partition(payments).forEach(chunk -> staging.stagePayments(businessDate, chunk));
        Chunks.partition(wallets).forEach(chunk -> staging.stageWallets(businessDate, chunk));
        Chunks.partition(matchStaged(businessDate))
                .forEach(chunk -> report.insertIgnoringDuplicates(businessDate, chunk));
    }

    /** The start of a fresh job: refuses an open date, then clears the date's staging (never the report). */
    public void startFresh(LocalDate businessDate) {
        requireClosed(businessDate);
        staging.clearStaging(businessDate);
    }

    /**
     * Downloads the payment statement; refuses an open date, a repeated payment id, an unknown state or another
     * currency before staging.
     */
    public List<PaymentLine> downloadPayments(LocalDate businessDate) {
        requireClosed(businessDate);
        return checkedPayments(businessDate);
    }

    /** Downloads the wallet statement; refuses an open date, a repeated payment id or an unknown status. */
    public List<WalletLine> downloadWallets(LocalDate businessDate) {
        requireClosed(businessDate);
        List<WalletLine> lines = source.wallets(businessDate);
        StatementMatcher.requireDistinctWallets(lines);
        return lines;
    }

    /**
     * Matches what is staged for the date with the statements of the day before and the day after (downloaded here:
     * they are only looked up, never staged), ordered by payment id.
     */
    public List<ReconResult> matchStaged(LocalDate businessDate) {
        LocalDate before = businessDate.minusDays(1);
        LocalDate after = businessDate.plusDays(1);
        List<PaymentLine> adjacentPayments = new ArrayList<>(checkedPayments(before));
        adjacentPayments.addAll(checkedPayments(after));
        List<WalletLine> adjacentWallets = new ArrayList<>(source.wallets(before));
        adjacentWallets.addAll(source.wallets(after));
        return StatementMatcher.match(
                staging.stagedPayments(businessDate),
                staging.stagedWallets(businessDate),
                adjacentPayments,
                adjacentWallets);
    }

    private List<PaymentLine> checkedPayments(LocalDate date) {
        List<PaymentLine> lines = source.payments(date);
        StatementMatcher.requireDistinctPayments(lines);
        for (PaymentLine line : lines) {
            if (!line.currency().equals(currency)) {
                throw new IllegalArgumentException("payment " + line.paymentId() + " is not in " + currency);
            }
        }
        return lines;
    }

    private void requireClosed(LocalDate businessDate) {
        // the newest closed date is today - 2: payments of today - 1 may still be settling past midnight
        LocalDate newestClosed = LocalDate.now(clock).minusDays(2);
        if (businessDate.isAfter(newestClosed)) {
            throw new IllegalArgumentException(
                    "business date " + businessDate + " is not closed; the newest closed date is " + newestClosed);
        }
    }
}
