package com.siddheshfulawade.payflow.recon.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.recon.ReconciliationApplication;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import com.siddheshfulawade.payflow.recon.support.ReconPostgres;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spring Batch job against a Testcontainers PostgreSQL recon database (Flyway: staging tables, recon_result, unique
 * (business_date, payment_id) on each; the Batch job repository tables). StatementSource is a stub serving generated
 * statements (no network). Job parameter: businessDate.
 */
@Tag("it")
@SpringBootTest(classes = ReconciliationApplication.class)
@Import(ReconJobRestartIT.Stubs.class)
class ReconJobRestartIT {

    private static final LocalDate DAY = LocalDate.parse("2026-11-26");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ReconPostgres.register(registry);
    }

    /** Statements the stub serves, and the two failures a test can switch on. */
    static final class StubSource implements StatementSource {

        List<PaymentLine> payments = new ArrayList<>();
        List<WalletLine> wallets = new ArrayList<>();
        volatile boolean failing;

        @Override
        public List<PaymentLine> payments(LocalDate businessDate) {
            if (failing) {
                throw new IllegalStateException("simulated HTTP 500");
            }
            return payments;
        }

        @Override
        public List<WalletLine> wallets(LocalDate businessDate) {
            if (failing) {
                throw new IllegalStateException("simulated HTTP 500");
            }
            return wallets;
        }
    }

    /** The real report store, except that the Nth call throws once: a crash in the middle of the report. */
    static final class FlakyReportStore implements ReportStore {

        private final ReportStore real;
        final AtomicInteger calls = new AtomicInteger();
        volatile int failOnCall = -1;

        FlakyReportStore(ReportStore real) {
            this.real = real;
        }

        @Override
        public int insertIgnoringDuplicates(LocalDate businessDate, List<ReconResult> chunk) {
            if (calls.incrementAndGet() == failOnCall) {
                throw new IllegalStateException("simulated crash");
            }
            return real.insertIgnoringDuplicates(businessDate, chunk);
        }
    }

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        StubSource stubSource() {
            return new StubSource();
        }

        @Bean
        @Primary
        FlakyReportStore flakyReportStore(@Qualifier("jdbcReportStore") ReportStore real) {
            return new FlakyReportStore(real);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job reconJob;

    @Autowired
    StubSource source;

    @Autowired
    FlakyReportStore report;

    @BeforeEach
    void cleanState() {
        // recon tables and the Batch job repository (children first)
        jdbc.execute("truncate table recon_result, recon_staging_payment, recon_staging_wallet");
        jdbc.execute("truncate table batch_step_execution_context, batch_step_execution, batch_job_execution_context,"
                + " batch_job_execution_params, batch_job_execution, batch_job_instance");
        source.failing = false;
        report.calls.set(0);
        report.failOnCall = -1;
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    /** n payments, all COMPLETED, each with a CAPTURED wallet line of the same amount. */
    private void matching(int n) {
        source.payments = new ArrayList<>();
        source.wallets = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            source.payments.add(new PaymentLine(id(i), i, "INR", "COMPLETED"));
            source.wallets.add(new WalletLine(id(i), i, "CAPTURED"));
        }
    }

    private JobExecution launch(LocalDate day) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("businessDate", day.toString())
                .toJobParameters();
        return jobOperator.start(reconJob, parameters);
    }

    private int resultRows(LocalDate day) {
        return jdbc.queryForObject("select count(*) from recon_result where business_date = ?", Integer.class, day);
    }

    @Test
    @DisplayName("REQ-PF-040 the job stages in chunks of 1,000 and writes one recon_result row per payment")
    void happyRun() throws Exception {
        // 2,500 matching payments, plus 2,501 with a different captured amount and 2,502 with no wallet line
        matching(2_500);
        source.payments.add(new PaymentLine(id(2_501), 10, "INR", "COMPLETED"));
        source.wallets.add(new WalletLine(id(2_501), 9, "CAPTURED"));
        source.payments.add(new PaymentLine(id(2_502), 10, "INR", "COMPLETED"));

        JobExecution execution = launch(DAY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resultRows(DAY)).isEqualTo(2_502);
        Map<String, Integer> outcomes = new java.util.TreeMap<>();
        jdbc.query("select outcome, count(*) as n from recon_result group by outcome", rs -> {
            outcomes.put(rs.getString("outcome"), rs.getInt("n"));
        });
        assertThat(outcomes)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("MATCHED", 2_500, "AMOUNT_MISMATCH", 1, "MISSING_IN_WALLET", 1));
        // 2,502 payment lines are read, then written in 3 chunks: 1,000 + 1,000 + 502
        StepExecution stagePayments = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals("stagePayments"))
                .findFirst()
                .orElseThrow();
        assertThat(stagePayments.getWriteCount()).isEqualTo(2_502);
        assertThat(stagePayments.getCommitCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName(
            "REQ-PF-041 a crash mid-run (the 2nd report chunk throws once), restart: no duplicate rows, no repeated staging")
    void restartAfterCrash() throws Exception {
        matching(2_500);
        report.failOnCall = 2;

        JobExecution first = launch(DAY);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(resultRows(DAY)).isEqualTo(1_000);

        JobExecution second = launch(DAY);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getInstanceId())
                .as("a restart of the failed instance, not a new one")
                .isEqualTo(first.getJobInstance().getInstanceId());
        assertThat(jdbc.queryForObject(
                        "select count(distinct payment_id) from recon_result where business_date = ?",
                        Integer.class,
                        DAY))
                .isEqualTo(2_500);
        assertThat(resultRows(DAY)).isEqualTo(2_500);
        // the staging steps completed in the first execution and were not run again
        assertThat(second.getStepExecutions().stream()
                        .filter(s -> s.getStepName().startsWith("stage"))
                        .filter(s -> s.getReadCount() > 0))
                .as("staging steps that read again in the restart")
                .isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from recon_staging_payment", Integer.class))
                .isEqualTo(2_500);
    }

    @Test
    @DisplayName("REQ-PF-041 the database refuses a second report row for the same business date and payment id")
    void uniqueKey() {
        UUID id = UUID.randomUUID();
        String insert = "insert into recon_result(business_date, payment_id, outcome) values (?, ?, 'MATCHED')";
        jdbc.update(insert, DAY, id);

        assertThatThrownBy(() -> jdbc.update(insert, DAY, id)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update(insert, DAY.plusDays(1), id);
        assertThat(jdbc.queryForObject("select count(*) from recon_result", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-041 launching a COMPLETED business date again is refused, never a second report")
    void completedDateAgain() throws Exception {
        matching(5);
        assertThat(launch(DAY).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThatThrownBy(() -> launch(DAY)).isInstanceOf(JobInstanceAlreadyCompleteException.class);

        assertThat(resultRows(DAY)).isEqualTo(5);
    }

    @Test
    @DisplayName(
            "REQ-PF-040 a failed download fails the job and writes no report row; a restart after recovery completes")
    void downloadFails() throws Exception {
        matching(5);
        source.failing = true;

        JobExecution first = launch(DAY);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(resultRows(DAY)).isZero();

        source.failing = false;
        JobExecution second = launch(DAY);

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resultRows(DAY)).isEqualTo(5);
    }

    @Test
    @DisplayName("REQ-PF-042 N payments reconcile through the real database; elapsed time is printed for the README")
    void measuredRunAgainstTheDatabase() throws Exception {
        int n = Integer.getInteger("recon.n", 10_000);
        matching(n);

        long start = System.nanoTime();
        JobExecution execution = launch(DAY);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(resultRows(DAY)).isEqualTo(n);
        System.out.println("REQ-PF-042 " + n + " payments reconciled through PostgreSQL in " + millis / 1000.0 + " s");
    }
}
