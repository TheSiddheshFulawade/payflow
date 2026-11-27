package com.siddheshfulawade.payflow.recon.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.recon.domain.Outcome;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** REQ-PF-040 and REQ-PF-041 on the run's orchestration, against in-memory ports (the database is in the IT). */
class ReconciliationRunTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-26");

    private InMemoryRecon ports;
    private ReconciliationRun run;

    @BeforeEach
    void setUp() {
        ports = new InMemoryRecon();
        run = new ReconciliationRun(ports, ports, ports);
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
    @DisplayName("REQ-PF-041 the same payment id on two business dates is two report rows")
    void otherDateIsOtherRow() {
        ports.generateMatching(2);
        run.run(DAY);

        run.run(DAY.plusDays(1));

        assertThat(ports.report).hasSize(4);
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
