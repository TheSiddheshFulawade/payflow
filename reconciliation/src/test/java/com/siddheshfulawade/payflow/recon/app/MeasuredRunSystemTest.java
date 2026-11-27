package com.siddheshfulawade.payflow.recon.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.recon.domain.Outcome;
import com.siddheshfulawade.payflow.recon.domain.ReconResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * REQ-PF-042 measured run. N payments are generated as two statements (all matching), reconciled, and the elapsed time
 * is printed and written to target/recon-measured-run.txt for the README. N is a parameter:
 * {@code -Drecon.n=100000} (SPEC PF-10); the default is 10,000, the stated count of the cut line.
 *
 * <p>This version times the matching, staging chunks and report writes against in-memory ports. The implementer adds
 * the same measurement against the real recon database (Testcontainers) in the job IT and publishes that figure.
 * Not run by default: {@code -Psystem}.
 */
@Tag("system")
class MeasuredRunSystemTest {

    @Test
    @DisplayName("REQ-PF-042 N payments reconcile to MATCHED only; elapsed time is recorded")
    void measuredRun() throws Exception {
        int n = Integer.getInteger("recon.n", 10_000);
        InMemoryRecon ports = new InMemoryRecon();
        ports.generateMatching(n);
        ReconciliationRun run = new ReconciliationRun(ports, ports, ports);

        long start = System.nanoTime();
        run.run(LocalDate.parse("2026-11-26"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        String line = n + " payments reconciled in " + elapsed.toMillis() / 1000.0 + " s on "
                + System.getProperty("os.name") + " / " + Runtime.getRuntime().availableProcessors() + " cpus / JDK "
                + System.getProperty("java.version");
        System.out.println("REQ-PF-042 " + line);
        Files.writeString(Path.of("target", "recon-measured-run.txt"), line + System.lineSeparator());

        // the invariant of SPEC section 7: a clean run reports only MATCHED, one row per payment
        assertThat(ports.report).hasSize(n);
        assertThat(ports.report.values()).extracting(ReconResult::outcome).containsOnly(Outcome.MATCHED);
    }
}
