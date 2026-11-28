package com.siddheshfulawade.payflow.recon.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.recon.domain.Outcome;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-PF-040 and REQ-PF-041 on the run's orchestration, against in-memory ports (the database is in the IT). */
class ReconciliationRunTest {

    private static final LocalDate DAY = InMemoryRecon.DAY;
    private static final LocalDate NEXT = DAY.plusDays(1);
    /** "today" is 2026-12-01 UTC: dates up to 2026-11-29 (today - 2) are closed. */
    private static final LocalDate TODAY = LocalDate.parse("2026-12-01");

    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private InMemoryRecon ports;
    private ReconciliationRun run;

    @BeforeEach
    void setUp() {
        ports = new InMemoryRecon();
        run = new ReconciliationRun(ports, ports, ports, CLOCK, "INR");
    }

    @Test
    @DisplayName("REQ-PF-040 a run writes one report row per payment id, MATCHED rows included")
    void reportsEveryPayment() {
        ports.generateMatching(3);
        ports.walletStatement.set(1, new WalletLine(InMemoryRecon.id(2), 1, "CAPTURED"));
        ports.paymentStatement.remove(2);

        run.run(DAY);

        assertThat(ports.report.values())
                .extracting(ReconResult::outcome)
                .containsExactly(Outcome.MATCHED, Outcome.AMOUNT_MISMATCH, Outcome.MISSING_IN_PAYMENTS);
    }

    @Test
    @DisplayName("REQ-PF-040 staging and report writes are in chunks of at most 1,000 rows")
    void chunksOf1000() {
        ports.generateMatching(2_500);

        run.run(DAY);

        // 2,500 payment lines + 2,500 wallet lines staged: 3 + 3 chunks; 2,500 report rows: 3 chunks
        assertThat(ports.stageChunkSizes).containsExactly(1_000, 1_000, 500, 1_000, 1_000, 500);
        assertThat(ports.reportChunkSizes).containsExactly(1_000, 1_000, 500);
        assertThat(ports.report).hasSize(2_500);
    }

    @Test
    @DisplayName("REQ-PF-041 a rerun after a completed run adds no report rows")
    void rerunAfterSuccess() {
        ports.generateMatching(1_200);
        run.run(DAY);
        int rows = ports.report.size();

        run.run(DAY);

        assertThat(ports.report).hasSize(rows).hasSize(1_200);
        assertThat(ports.stagedPayments).hasSize(1_200);
        assertThat(ports.stagedWallets).hasSize(1_200);
    }

    @Test
    @DisplayName("REQ-PF-041 a crash while staging, then a rerun, gives exactly one report row per payment")
    void crashWhileStaging() {
        ports.generateMatching(2_500);
        ports.failOnWriteCall(2); // second staging chunk

        assertThatThrownBy(() -> run.run(DAY)).isInstanceOf(IllegalStateException.class);
        assertThat(ports.report).as("no report before staging finished").isEmpty();

        run.run(DAY);

        assertThat(ports.report).hasSize(2_500);
        assertThat(ports.report.values()).extracting(ReconResult::outcome).containsOnly(Outcome.MATCHED);
        assertThat(ports.stagedPayments).hasSize(2_500);
    }

    @Test
    @DisplayName("REQ-PF-041 a crash after the first report chunk, then a rerun, leaves no duplicate and no gap")
    void crashWhileReporting() {
        ports.generateMatching(2_500);
        // 6 staging calls, then report chunk 1 (call 7) succeeds, report chunk 2 (call 8) crashes
        ports.failOnWriteCall(8);

        assertThatThrownBy(() -> run.run(DAY)).isInstanceOf(IllegalStateException.class);
        assertThat(ports.report).hasSize(1_000);

        run.run(DAY);

        assertThat(ports.report).hasSize(2_500);
        assertThat(ports.report.values()).extracting(ReconResult::outcome).containsOnly(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-041 the payments of two business dates are reported on their own dates, one row each")
    void otherDateIsOtherRow() {
        ports.generateMatching(2);
        ports.on(NEXT, List.of(pay(3, 30, "COMPLETED")), List.of(wal(3, 30, "CAPTURED")));
        run.run(DAY);

        run.run(NEXT);

        assertThat(ports.report.keySet())
                .containsExactly(
                        DAY + "/" + InMemoryRecon.id(1),
                        DAY + "/" + InMemoryRecon.id(2),
                        NEXT + "/" + InMemoryRecon.id(3));
    }

    // ---- midnight boundary (decision: a pair is reported once, on the payment line's date) ----

    private static PaymentLine pay(int n, long amount, String state) {
        return new PaymentLine(InMemoryRecon.id(n), amount, "INR", state);
    }

    private static WalletLine wal(int n, long captured, String status) {
        return new WalletLine(InMemoryRecon.id(n), captured, status);
    }

    private String key(LocalDate date, int n) {
        return date + "/" + InMemoryRecon.id(n);
    }

    @Test
    @DisplayName("REQ-PF-040 captured on D, COMPLETED on D+1: MATCHED once, on D+1, never MISSING on either day")
    void capturedBeforeMidnightCompletedAfter() {
        ports.on(DAY, List.of(), List.of(wal(1, 500, "CAPTURED")));
        ports.on(NEXT, List.of(pay(1, 500, "COMPLETED")), List.of());

        run.run(DAY);
        assertThat(ports.report)
                .as("D: the wallet line belongs to a payment of D+1")
                .isEmpty();
        run.run(NEXT);

        assertThat(ports.report).containsOnlyKeys(key(NEXT, 1));
        assertThat(ports.report.get(key(NEXT, 1)).outcome()).isEqualTo(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-040 COMPLETED on D, its wallet line on D+1: MATCHED once, on D, never MISSING on either day")
    void completedBeforeMidnightWalletAfter() {
        ports.on(DAY, List.of(pay(1, 500, "COMPLETED")), List.of());
        ports.on(NEXT, List.of(), List.of(wal(1, 500, "CAPTURED")));

        run.run(DAY);
        run.run(NEXT);

        assertThat(ports.report).containsOnlyKeys(key(DAY, 1));
        assertThat(ports.report.get(key(DAY, 1)).outcome()).isEqualTo(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-040 the previous day's lines count too: payment on D, wallet line on D-1")
    void walletTheDayBefore() {
        ports.on(DAY.minusDays(1), List.of(), List.of(wal(1, 500, "CAPTURED")));
        ports.generateMatching(0);
        ports.paymentStatement.add(pay(1, 500, "COMPLETED"));

        run.run(DAY);

        assertThat(ports.report).containsOnlyKeys(key(DAY, 1));
        assertThat(ports.report.get(key(DAY, 1)).outcome()).isEqualTo(Outcome.MATCHED);
    }

    @Test
    @DisplayName("REQ-PF-040 a wallet line two days away does not count: the payment is MISSING_IN_WALLET")
    void windowIsOneDay() {
        ports.paymentStatement.add(pay(1, 500, "COMPLETED"));
        ports.on(DAY.plusDays(2), List.of(), List.of(wal(1, 500, "CAPTURED")));

        run.run(DAY);

        assertThat(ports.report.get(key(DAY, 1)).outcome()).isEqualTo(Outcome.MISSING_IN_WALLET);
    }

    @Test
    @DisplayName(
            "REQ-PF-040 a truly missing wallet line and a truly missing payment line are reported on their own date")
    void trulyMissingStillReported() {
        ports.paymentStatement.add(pay(1, 500, "COMPLETED"));
        ports.walletStatement.add(wal(2, 7, "CAPTURED"));

        run.run(DAY);

        assertThat(ports.report.get(key(DAY, 1)).outcome()).isEqualTo(Outcome.MISSING_IN_WALLET);
        assertThat(ports.report.get(key(DAY, 2)).outcome()).isEqualTo(Outcome.MISSING_IN_PAYMENTS);
        assertThat(ports.report).hasSize(2);
    }

    // ---- closed dates, currency, fresh staging ----

    @Test
    @DisplayName("REQ-PF-040 a date that is not closed (after today - 2, UTC) is refused before any download")
    void openDateRefused() {
        for (LocalDate open : List.of(TODAY.minusDays(1), TODAY, TODAY.plusDays(1))) {
            assertThatThrownBy(() -> run.run(open))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(open.toString());
        }
        assertThat(ports.downloads).isZero();
        assertThat(ports.report).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-040 the newest closable date, today - 2, runs")
    void newestClosedDateRuns() {
        ports.generateMatching(1);
        ports.on(TODAY.minusDays(2), List.of(pay(9, 9, "COMPLETED")), List.of(wal(9, 9, "CAPTURED")));

        run.run(TODAY.minusDays(2));

        assertThat(ports.report).containsOnlyKeys(key(TODAY.minusDays(2), 9));
    }

    @Test
    @DisplayName("REQ-PF-040 a payment line in another currency stops the run before anything is staged or reported")
    void foreignCurrencyStopsRun() {
        ports.generateMatching(2);
        ports.paymentStatement.set(1, new PaymentLine(InMemoryRecon.id(2), 5, "USD", "COMPLETED"));

        assertThatThrownBy(() -> run.run(DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(InMemoryRecon.id(2).toString());

        assertThat(ports.stagedPayments).isEmpty();
        assertThat(ports.report).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-041 a fresh run clears the date's old staging rows, so a stale line never reaches the report")
    void freshRunClearsStaleStaging() {
        ports.generateMatching(2);
        // left over from an earlier download of the same date: payment 99 is no longer in the statements
        ports.stagedPayments.put(DAY + "/" + InMemoryRecon.id(99), pay(99, 1, "COMPLETED"));
        ports.stagedWallets.put(DAY + "/" + InMemoryRecon.id(98), wal(98, 1, "CAPTURED"));

        run.run(DAY);

        assertThat(ports.report).containsOnlyKeys(key(DAY, 1), key(DAY, 2));
        assertThat(ports.stagedPayments).doesNotContainKey(DAY + "/" + InMemoryRecon.id(99));
        assertThat(ports.stagedWallets).doesNotContainKey(DAY + "/" + InMemoryRecon.id(98));
    }

    @Test
    @DisplayName("REQ-PF-041 clearing staging never touches the report: rows written before stay")
    void clearingStagingKeepsReport() {
        ports.generateMatching(2);
        run.run(DAY);

        ports.clearStaging(DAY);

        assertThat(ports.stagedPayments).isEmpty();
        assertThat(ports.report).hasSize(2);
    }

    @Test
    @DisplayName("REQ-PF-040 a duplicate line in a statement stops the run before any report row is written")
    void duplicateStopsRun() {
        ports.generateMatching(2);
        PaymentLine dup = ports.paymentStatement.get(0);
        ports.paymentStatement.add(dup);

        assertThatThrownBy(() -> run.run(DAY)).isInstanceOf(RuntimeException.class);

        assertThat(ports.report).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-040 two empty statements give an empty report and no failure")
    void emptyDay() {
        run.run(DAY);

        assertThat(ports.report).isEmpty();
        assertThat(List.copyOf(ports.stageChunkSizes)).isEmpty();
    }
}
