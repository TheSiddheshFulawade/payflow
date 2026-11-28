package com.siddheshfulawade.payflow.recon.infra;

import com.siddheshfulawade.payflow.recon.app.Chunks;
import com.siddheshfulawade.payflow.recon.app.ReconciliationRun;
import com.siddheshfulawade.payflow.recon.app.ReportStore;
import com.siddheshfulawade.payflow.recon.app.StagingStore;
import com.siddheshfulawade.payflow.recon.app.StatementSource;
import com.siddheshfulawade.payflow.recon.domain.PaymentLine;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import com.siddheshfulawade.payflow.recon.domain.WalletLine;
import java.time.Clock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The end-of-day job: download and stage the payment statement, download and stage the wallet statement, match and
 * write the report. Each step reads in memory and writes in chunks of {@link Chunks#SIZE}, one transaction per chunk.
 *
 * <p>Invariant (REQ-PF-041): the job instance is identified by {@code businessDate}. Relaunching a FAILED instance
 * restarts it: steps that COMPLETED are skipped (their staging stays), the failed step runs again from its start, and
 * because every write is idempotent on (business date, payment id) that adds only what was missing. Relaunching a
 * COMPLETED instance is refused by Spring Batch. The rules themselves live in {@link ReconciliationRun}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconProperties.class)
class ReconJobConfig {

    /** The closed-date rule reads "today" from here; UTC, the same day boundary as the statements. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReconciliationRun reconciliationRun(
            StatementSource source, StagingStore staging, ReportStore report, Clock clock, ReconProperties properties) {
        return new ReconciliationRun(source, staging, report, clock, properties.currency());
    }

    @Bean
    Job reconJob(JobRepository repository, Step stagePaymentsStep, Step stageWalletsStep, Step reportStep) {
        return new JobBuilder("reconJob", repository)
                .start(stagePaymentsStep)
                .next(stageWalletsStep)
                .next(reportStep)
                .build();
    }

    @Bean
    Step stagePaymentsStep(
            JobRepository repository,
            PlatformTransactionManager transactions,
            ReconciliationRun run,
            StagingStore staging) {
        // the first step of a job starts fresh: it clears the date's staging (a restart of a later step skips this
        // step)
        var reader = new BusinessDateStep.Reader<PaymentLine>(date -> {
            run.startFresh(date);
            return run.downloadPayments(date);
        });
        var writer = new BusinessDateStep.Writer<PaymentLine>(staging::stagePayments);
        return new StepBuilder("stagePayments", repository)
                .<PaymentLine, PaymentLine>chunk(Chunks.SIZE)
                .reader(reader)
                .writer(writer)
                .transactionManager(transactions)
                .listener(reader)
                .listener(writer)
                .build();
    }

    @Bean
    Step stageWalletsStep(
            JobRepository repository,
            PlatformTransactionManager transactions,
            ReconciliationRun run,
            StagingStore staging) {
        var reader = new BusinessDateStep.Reader<WalletLine>(run::downloadWallets);
        var writer = new BusinessDateStep.Writer<WalletLine>(staging::stageWallets);
        return new StepBuilder("stageWallets", repository)
                .<WalletLine, WalletLine>chunk(Chunks.SIZE)
                .reader(reader)
                .writer(writer)
                .transactionManager(transactions)
                .listener(reader)
                .listener(writer)
                .build();
    }

    @Bean
    Step reportStep(
            JobRepository repository,
            PlatformTransactionManager transactions,
            ReconciliationRun run,
            ReportStore report) {
        var reader = new BusinessDateStep.Reader<ReconResult>(run::matchStaged);
        var writer = new BusinessDateStep.Writer<ReconResult>(report::insertIgnoringDuplicates);
        return new StepBuilder("report", repository)
                .<ReconResult, ReconResult>chunk(Chunks.SIZE)
                .reader(reader)
                .writer(writer)
                .transactionManager(transactions)
                .listener(reader)
                .listener(writer)
                .build();
    }
}
