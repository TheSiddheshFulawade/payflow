package com.siddheshfulawade.payflow.recon.app;

import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * All three ports in memory. Staging and report rows are keyed like the database keys them (business date + payment
 * id), so a repeated write replaces or is ignored exactly as the unique keys will. {@code failOnCall} makes the Nth
 * write call (staging or report, counted together) throw once: a crash in the middle of a run.
 */
final class InMemoryRecon implements StatementSource, StagingStore, ReportStore {

    List<PaymentLine> paymentStatement = new ArrayList<>();
    List<WalletLine> walletStatement = new ArrayList<>();
    final Map<String, PaymentLine> stagedPayments = new TreeMap<>();
    final Map<String, WalletLine> stagedWallets = new TreeMap<>();
    final Map<String, ReconResult> report = new TreeMap<>();
    final List<Integer> stageChunkSizes = new ArrayList<>();
    final List<Integer> reportChunkSizes = new ArrayList<>();
    /** every row ever handed to insertIgnoringDuplicates, duplicates included */
    int reportRowsOffered;

    private int writeCalls;
    private int failOnCall = -1;

    void failOnWriteCall(int n) {
        failOnCall = n;
    }

    private void maybeCrash() {
        if (++writeCalls == failOnCall) {
            throw new IllegalStateException("simulated crash");
        }
    }

    private static String key(LocalDate date, UUID id) {
        return date + "/" + id;
    }

    @Override
    public List<PaymentLine> payments(LocalDate businessDate) {
        return paymentStatement;
    }

    @Override
    public List<WalletLine> wallets(LocalDate businessDate) {
        return walletStatement;
    }

    @Override
    public void stagePayments(LocalDate date, List<PaymentLine> chunk) {
        maybeCrash();
        stageChunkSizes.add(chunk.size());
        chunk.forEach(l -> stagedPayments.put(key(date, l.paymentId()), l));
    }

    @Override
    public void stageWallets(LocalDate date, List<WalletLine> chunk) {
        maybeCrash();
        stageChunkSizes.add(chunk.size());
        chunk.forEach(l -> stagedWallets.put(key(date, l.paymentId()), l));
    }

    @Override
    public List<PaymentLine> stagedPayments(LocalDate date) {
        return stagedPayments.entrySet().stream()
                .filter(e -> e.getKey().startsWith(date + "/"))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public List<WalletLine> stagedWallets(LocalDate date) {
        return stagedWallets.entrySet().stream()
                .filter(e -> e.getKey().startsWith(date + "/"))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public int insertIgnoringDuplicates(LocalDate date, List<ReconResult> chunk) {
        maybeCrash();
        reportChunkSizes.add(chunk.size());
        reportRowsOffered += chunk.size();
        int inserted = 0;
        for (ReconResult r : chunk) {
            if (report.putIfAbsent(key(date, r.paymentId()), r) == null) {
                inserted++;
            }
        }
        return inserted;
    }

    // ---- statement generation ----

    static UUID id(int n) {
        return new UUID(0, n);
    }

    /** n payments, all COMPLETED with a matching CAPTURED wallet line of the same amount. */
    void generateMatching(int n) {
        paymentStatement = new ArrayList<>(n);
        walletStatement = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            long amount = 1 + (i % 5_000_000);
            paymentStatement.add(new PaymentLine(id(i), amount, "INR", "COMPLETED"));
            walletStatement.add(new WalletLine(id(i), amount, "CAPTURED"));
        }
    }
}
