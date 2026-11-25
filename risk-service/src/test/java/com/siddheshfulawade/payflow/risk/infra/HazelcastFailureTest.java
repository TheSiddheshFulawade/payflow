package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.exception.CallerNotMemberException;
import com.hazelcast.spi.exception.PartitionMigratingException;
import com.hazelcast.spi.exception.RetryableHazelcastException;
import com.hazelcast.spi.exception.TargetNotMemberException;
import com.hazelcast.spi.exception.WrongTargetException;
import com.siddheshfulawade.payflow.messaging.RetriedBySenderException;
import com.siddheshfulawade.payflow.risk.app.VelocityUnavailableException;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What the velocity counter does when the cluster does not answer, or answers with an error. No members started. */
class HazelcastFailureTest {

    private static final RiskLimits LIMITS = RiskLimits.defaults();

    private final IMap<String, VelocityWindow> map = mock();
    private final HazelcastInstance hazelcast = mock();

    private HazelcastVelocityCounter counter() {
        when(hazelcast.<String, VelocityWindow>getMap(HazelcastVelocityCounter.MAP_NAME))
                .thenReturn(map);
        return new HazelcastVelocityCounter(hazelcast, LIMITS);
    }

    @Test
    @DisplayName("REQ-PF-032 a check the cluster never answers fails after about 1 s as VelocityUnavailableException:"
            + " never approves, never hangs, the sender re-sends")
    void unansweredCheckFailsAfterTheBound() {
        when(map.submitToKey(any(), any())).thenReturn(new CompletableFuture<>());
        var counter = counter();
        UUID payment = UUID.randomUUID();
        long start = System.nanoTime();

        Throwable failure = catchThrowable(() -> counter.allow("secret-payer", payment, Instant.EPOCH));

        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(failure)
                .isInstanceOf(VelocityUnavailableException.class)
                .isInstanceOf(RetriedBySenderException.class);
        assertThat(failure.getMessage()).doesNotContain("secret-payer").doesNotContain(payment.toString());
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(900)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("REQ-PF-032 the bound is 1 s, and shorter than Hazelcast's own call timeout, which stays 5 s")
    void boundIsOneSecond() {
        assertThat(HazelcastVelocityCounter.CALL_TIMEOUT).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("REQ-PF-032 an answer in time is passed on unchanged, true and false")
    void answersPassThrough() {
        when(map.submitToKey(any(), any()))
                .thenReturn(CompletableFuture.<Object>completedFuture(true))
                .thenReturn(CompletableFuture.<Object>completedFuture(false));
        var counter = counter();

        assertThat(counter.allow("payer", UUID.randomUUID(), Instant.EPOCH)).isTrue();
        assertThat(counter.allow("payer", UUID.randomUUID(), Instant.EPOCH)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("errorsBetweenMembers")
    @DisplayName("REQ-PF-032 every cluster error surfaces at once as VelocityUnavailableException with a fixed text")
    void clusterErrorsBecomeUnavailable(Throwable error) {
        when(map.submitToKey(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new HazelcastException("boom secret-payer", error)));
        var counter = counter();
        long start = System.nanoTime();

        Throwable failure = catchThrowable(() -> counter.allow("secret-payer", UUID.randomUUID(), Instant.EPOCH));

        assertThat(failure).isInstanceOf(VelocityUnavailableException.class);
        assertThat(failure.getMessage()).doesNotContain("secret-payer");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(900));
    }

    @ParameterizedTest
    @MethodSource("errorsBetweenMembers")
    @DisplayName("REQ-PF-032 an error thrown at the call itself (member shut down) surfaces the same way")
    void errorThrownAtTheCallBecomesUnavailable(Throwable error) {
        when(map.submitToKey(any(), any())).thenThrow((RuntimeException) wrap(error));
        var counter = counter();

        Throwable failure = catchThrowable(() -> counter.allow("secret-payer", UUID.randomUUID(), Instant.EPOCH));

        assertThat(failure).isInstanceOf(VelocityUnavailableException.class);
        assertThat(failure.getMessage()).doesNotContain("secret-payer");
    }

    private static Throwable wrap(Throwable error) {
        return error instanceof RuntimeException ? error : new HazelcastException("wrapped", error);
    }

    static List<Throwable> errorsBetweenMembers() {
        // every Hazelcast error the allow-list names, nothing else (the list is exact, see
        // HazelcastSerializationFilterTest)
        return List.of(
                new HazelcastException("generic"),
                new HazelcastInstanceNotActiveException("member is shutting down"),
                new RetryableHazelcastException("retry"),
                new PartitionMigratingException(null, 1, "operation", "service"),
                new WrongTargetException("wrong target"),
                new CallerNotMemberException("caller is not a member"),
                new TargetNotMemberException("target is not a member"));
    }

    @ParameterizedTest
    @MethodSource("errorsBetweenMembers")
    @DisplayName(
            "REQ-PF-032 the filter lets Hazelcast's own errors through, so a migrating partition is retried at once")
    void hazelcastErrorsPassTheFilter(Throwable error) {
        var properties = new HazelcastProperties("c", 5701, List.of("127.0.0.1:5701"), "127.0.0.1");
        InternalSerializationService serialization = new DefaultSerializationServiceBuilder()
                .setConfig(HazelcastConfigFactory.build(properties, LIMITS).getSerializationConfig())
                .build();
        try {
            Throwable received = serialization.toObject(serialization.toData(error));

            assertThat(received).isInstanceOf(error.getClass()).hasMessage(error.getMessage());
        } finally {
            serialization.dispose();
        }
    }
}
