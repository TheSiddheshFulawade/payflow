package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.map.IMap;
import com.siddheshfulawade.payflow.risk.app.VelocityUnavailableException;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A velocity failure is expected during a cluster outage and happens once per check, so it is one WARN line with
 * exception class names, no stack trace: library texts may quote the payer, and a stack trace per check floods the log.
 */
class VelocityFailureLogTest {

    private static final String PAYER = "secret-payer-31";

    private final IMap<String, VelocityWindow> map = mock();
    private final HazelcastInstance hazelcast = mock();
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        serviceLogger = (Logger) LoggerFactory.getLogger("com.siddheshfulawade.payflow");
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
        when(hazelcast.<String, VelocityWindow>getMap(HazelcastVelocityCounter.MAP_NAME))
                .thenReturn(map);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
    }

    @Test
    @DisplayName("REQ-PF-032 a failed velocity call logs one WARN with the exception class names: no payer, no payment"
            + " id, no cause text, no stack trace")
    void failedCallLogsOneSafeWarning() {
        var cause = new HazelcastInstanceNotActiveException("member of " + PAYER + " is shutting down");
        UUID payment = UUID.randomUUID();
        when(map.submitToKey(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new HazelcastException("boom " + PAYER, cause)));
        var counter = new HazelcastVelocityCounter(hazelcast, RiskLimits.defaults());

        Throwable failure = catchThrowable(() -> counter.allow(PAYER, payment, Instant.EPOCH));

        assertThat(failure).isInstanceOf(VelocityUnavailableException.class);
        assertThat(warnings()).hasSize(1);
        ILoggingEvent warning = warnings().get(0);
        assertThat(warning.getFormattedMessage())
                .contains(VelocityUnavailableException.class.getSimpleName())
                .contains(cause.getClass().getSimpleName())
                .doesNotContain(PAYER)
                .doesNotContain(payment.toString())
                .doesNotContain("boom")
                .doesNotContain("shutting down");
        assertThat(warning.getThrowableProxy()).as("no stack trace").isNull();
        assertThat(logs.list)
                .as("nothing else carries the account either")
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(PAYER));
    }

    @Test
    @DisplayName("REQ-PF-032 an error thrown at the call itself (member shut down) is logged the same way, once")
    void errorAtTheCallLogsOneSafeWarning() {
        var thrown = new HazelcastInstanceNotActiveException("member of " + PAYER + " is shut down");
        when(map.submitToKey(any(), any())).thenThrow(thrown);
        var counter = new HazelcastVelocityCounter(hazelcast, RiskLimits.defaults());

        Throwable failure = catchThrowable(() -> counter.allow(PAYER, UUID.randomUUID(), Instant.EPOCH));

        assertThat(failure).isInstanceOf(VelocityUnavailableException.class);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage())
                .contains(thrown.getClass().getSimpleName())
                .doesNotContain(PAYER)
                .doesNotContain("shut down");
        assertThat(warnings().get(0).getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("REQ-PF-032 an answer in time logs no warning")
    void answerInTimeLogsNothing() {
        when(map.submitToKey(any(), any())).thenReturn(CompletableFuture.<Object>completedFuture(true));
        var counter = new HazelcastVelocityCounter(hazelcast, RiskLimits.defaults());

        counter.allow(PAYER, UUID.randomUUID(), Instant.EPOCH);

        assertThat(warnings()).isEmpty();
    }
}
