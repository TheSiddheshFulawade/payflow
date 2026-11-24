package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.contracts.Topics;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A failing payment is rolled back and logged; it never stops the others and is never lost (REQ-PF-012, 020). */
class PaymentSweeperFailureTest extends PaymentIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void virtualTimeAndLogs() {
        clock.set(T0);
        serviceLogger = (Logger) LoggerFactory.getLogger("com.siddheshfulawade.payflow.payment");
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("REQ-PF-012 one broken payment is rolled back and logged; the others are still re-sent")
    void oneBrokenPaymentDoesNotStopTheOthers() {
        Payment first = newPayment();
        Payment broken = newPayment();
        Payment last = newPayment();
        clock.advance(Duration.ofSeconds(11));
        makeOutboxInsertFailFor(broken.id());
        long brokenVersion = version(broken.id());

        int handled = sweeper.sweep();

        assertThat(handled).as("only the two healthy payments").isEqualTo(2);
        assertThat(stepAttempts(first.id())).isEqualTo(2);
        assertThat(stepAttempts(last.id())).isEqualTo(2);
        assertThat(stepAttempts(broken.id())).isEqualTo(1);
        assertThat(version(broken.id())).isEqualTo(brokenVersion);
        assertThat(commands(broken.id(), ReserveFunds.class)).hasSize(1);
        assertThat(logs.list)
                .as("the failure is logged")
                .anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
        assertThat(logs.list)
                .allSatisfy(e -> assertThat(e.getFormattedMessage())
                        .doesNotContain("alice")
                        .doesNotContain("bob"));

        dropFailureTriggers();
        assertThat(sweeper.sweep())
                .as("the broken payment is not lost: its deadline still stands")
                .isEqualTo(1);
        assertThat(stepAttempts(broken.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-020 a failed outbox append rolls back the re-send: no attempt, no deadline, no command")
    void failedAppendRollsBackTheResend() {
        Payment p = newPayment();
        clock.advance(Duration.ofSeconds(11));
        makeOutboxInsertFail(Topics.WALLET_COMMANDS);
        Snapshot before = snapshotAll();

        int handled = sweeper.sweep();

        assertThat(handled).isZero();
        assertThat(snapshotAll()).isEqualTo(before);

        dropFailureTriggers();
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(stepAttempts(p.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-020 a failed append of the ReleaseFunds rolls back the compensation: still the same step")
    void failedAppendRollsBackTheCompensation() {
        Payment p = newPayment();
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(31));
            sweeper.sweep();
        }
        assertThat(stepAttempts(p.id())).isEqualTo(4);
        clock.advance(Duration.ofSeconds(31));
        makeOutboxInsertFail(Topics.WALLET_COMMANDS);
        Snapshot before = snapshotAll();

        assertThat(sweeper.sweep()).isZero();

        assertThat(snapshotAll()).isEqualTo(before);
        assertThat(state(p.id())).isEqualTo("RESERVING");
        assertThat(events(p.id())).doesNotContain("RESERVING->RELEASING");

        dropFailureTriggers();
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(state(p.id())).isEqualTo("RELEASING");
        assertThat(commands(p.id(), ReleaseFunds.class)).hasSize(1);
    }
}
