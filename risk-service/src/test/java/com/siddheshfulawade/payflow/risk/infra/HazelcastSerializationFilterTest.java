package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.core.OperationTimeoutException;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.spi.exception.RetryableIOException;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Java-deserialization allow-list of the members: exactly the classes the velocity map and Hazelcast's own errors
 * need, by class name. No members are started; the filter is applied by Hazelcast's serialization service alone.
 */
class HazelcastSerializationFilterTest {

    private static final RiskLimits LIMITS = RiskLimits.defaults();

    /** Exactly what travels between members: the velocity value and processor, and Hazelcast's retryable errors. */
    private static final List<String> ALLOWED = List.of(
            "com.siddheshfulawade.payflow.risk.domain.VelocityWindow",
            "com.siddheshfulawade.payflow.risk.infra.VelocityEntryProcessor",
            "java.util.CollSer",
            "java.util.UUID",
            "java.lang.Long",
            "java.lang.Number",
            "com.hazelcast.core.HazelcastException",
            "com.hazelcast.core.HazelcastInstanceNotActiveException",
            "com.hazelcast.spi.exception.RetryableHazelcastException",
            "com.hazelcast.spi.exception.PartitionMigratingException",
            "com.hazelcast.spi.exception.WrongTargetException",
            "com.hazelcast.spi.exception.CallerNotMemberException",
            "com.hazelcast.spi.exception.TargetNotMemberException",
            "java.lang.Throwable",
            "java.lang.Exception",
            "java.lang.RuntimeException",
            "java.lang.IllegalStateException",
            "java.lang.StackTraceElement",
            "[Ljava.lang.StackTraceElement;",
            "java.util.Collections$EmptyList");

    /** A PayFlow class in a PayFlow package that is not one of the two the members need. */
    record NotForMembers(int value) implements Serializable {}

    private static JavaSerializationFilterConfig filter() {
        var properties = new HazelcastProperties("c", 5701, List.of("127.0.0.1:5701"), "127.0.0.1");
        return HazelcastConfigFactory.build(properties, LIMITS)
                .getSerializationConfig()
                .getJavaSerializationFilterConfig();
    }

    // ---- the config itself ----

    @Test
    @DisplayName(
            "REQ-PF-032 the allow-list names exact classes: no prefixes, no packages, Hazelcast's defaults are off")
    void allowListIsExactClassNamesOnly() {
        JavaSerializationFilterConfig filter = filter();

        assertThat(filter.isDefaultsDisabled()).as("defaults disabled").isTrue();
        assertThat(filter.getWhitelist().getPrefixes()).as("prefixes").isEmpty();
        assertThat(filter.getWhitelist().getPackages()).as("packages").isEmpty();
        assertThat(filter.getWhitelist().getClasses()).containsExactlyInAnyOrderElementsOf(ALLOWED);
        assertThat(filter.getBlacklist().isEmpty())
                .as("nothing needs a blacklist")
                .isTrue();
    }

    // ---- what must be refused ----

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("a JDK type Hazelcast sends by Java serialization", new AtomicLong(7)),
                Arguments.of("a Hazelcast exception that is not on the list", new OperationTimeoutException("slow")),
                Arguments.of(
                        "a Hazelcast exception whose base class (IOException) is not on the list",
                        new RetryableIOException("io")),
                Arguments.of("a Spring class", new org.springframework.util.LinkedMultiValueMap<String, String>()),
                Arguments.of(
                        "a PayFlow Serializable class that is not VelocityWindow or the processor",
                        new NotForMembers(1)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("refused")
    @DisplayName("REQ-PF-032 deserialising anything else is refused by the filter, not by luck")
    void everythingElseIsRefused(String what, Object value) {
        InternalSerializationService serialization = serialization();
        try {
            var data = serialization.toData(value);

            assertThatThrownBy(() -> serialization.toObject(data))
                    .as(what)
                    .hasRootCauseInstanceOf(SecurityException.class)
                    .rootCause()
                    .hasMessageContaining("is not allowed");
        } finally {
            serialization.dispose();
        }
    }

    private static InternalSerializationService serialization() {
        var properties = new HazelcastProperties("c", 5701, List.of("127.0.0.1:5701"), "127.0.0.1");
        return new DefaultSerializationServiceBuilder()
                .setConfig(HazelcastConfigFactory.build(properties, LIMITS).getSerializationConfig())
                .build();
    }
}
