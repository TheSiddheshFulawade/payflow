package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.map.IMap;
import com.hazelcast.spi.exception.PartitionMigratingException;
import com.hazelcast.spi.exception.RetryableIOException;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import com.siddheshfulawade.payflow.risk.domain.VelocityWindow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What the velocity counter does when the cluster does not answer, or answers with an error. No members started. */
class HazelcastFailureTest {

    private static final RiskLimits LIMITS = RiskLimits.defaults();

    @Test
    @DisplayName("REQ-PF-032 a check the cluster never answers fails after the bound: it never approves, never hangs")
    void unansweredCheckFails() {
        IMap<String, VelocityWindow> map = mock();
        HazelcastInstance hazelcast = mock();
        when(hazelcast.<String, VelocityWindow>getMap(HazelcastVelocityCounter.MAP_NAME))
                .thenReturn(map);
        when(map.submitToKey(any(), any())).thenReturn(new CompletableFuture<>());
        var counter = new HazelcastVelocityCounter(hazelcast, LIMITS);

        assertThatThrownBy(() -> counter.allow("payer", UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    static List<Throwable> errorsBetweenMembers() {
        return List.of(
                new PartitionMigratingException(null, 1, "operation", "service"),
                new RetryableIOException("connection lost"),
                new HazelcastInstanceNotActiveException("member is shutting down"));
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
