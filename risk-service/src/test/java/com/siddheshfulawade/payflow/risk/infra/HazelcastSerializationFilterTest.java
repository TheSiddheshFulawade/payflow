package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.config.ClassFilter;
import com.hazelcast.config.JavaSerializationFilterConfig;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.siddheshfulawade.payflow.risk.domain.RiskLimits;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
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
        // the velocity classes, their fields and the base errors; Hazelcast's own errors are pinned by the scan below
        assertThat(filter.getWhitelist().getClasses()).containsAll(ALLOWED);
        assertThat(filter.getBlacklist().isEmpty())
                .as("nothing needs a blacklist")
                .isTrue();
    }

    // ---- what must be refused ----

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("a JDK type Hazelcast sends by Java serialization", new AtomicLong(7)),
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

    // ---- Hazelcast's own errors, found by scanning the jar, not hand-picked ----

    /** Every concrete Hazelcast Throwable that is sent as a Java object, with its base classes up to Throwable. */
    private static List<String> hazelcastThrowablesAndBases() throws Exception {
        var found = new TreeSet<String>();
        var jar = java.nio.file.Path.of(HazelcastException.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        try (var jarFile = new JarFile(jar.toFile())) {
            for (var entry : jarFile.stream().toList()) {
                String name = entry.getName();
                // shaded/ holds bundled third-party libraries (Jackson, snakeyaml...), not Hazelcast's own errors
                if (!name.startsWith("com/hazelcast/")
                        || name.startsWith("com/hazelcast/shaded/")
                        || !name.endsWith(".class")) {
                    continue;
                }
                String className =
                        name.substring(0, name.length() - ".class".length()).replace('/', '.');
                Class<?> type;
                try {
                    type = Class.forName(className, false, HazelcastException.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError optionalDependencyMissing) {
                    continue;
                }
                boolean concrete = !type.isInterface() && !Modifier.isAbstract(type.getModifiers());
                // Hazelcast's own serializers take DataSerializable before Java serialization
                boolean javaSerialized = !com.hazelcast.nio.serialization.DataSerializable.class.isAssignableFrom(type);
                if (Throwable.class.isAssignableFrom(type) && concrete && javaSerialized) {
                    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                        found.add(c.getName());
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    @Test
    @DisplayName("REQ-PF-032 the scan finds Hazelcast's errors, among them the one that stopped a member join")
    void scanFindsThePartitionStateVersionMismatch() throws Exception {
        assertThat(hazelcastThrowablesAndBases())
                .contains("com.hazelcast.internal.partition.PartitionStateVersionMismatchException")
                .hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("REQ-PF-032 every Hazelcast error a member can send, and its base classes, is readable by the filter")
    void everyHazelcastErrorIsAllowed() throws Exception {
        ClassFilter allowed = filter().getWhitelist();

        var refused = hazelcastThrowablesAndBases().stream()
                .filter(name -> !allowed.isListed(name))
                .toList();

        assertThat(refused).as("Hazelcast errors the filter refuses").isEmpty();
    }

    /** The entries written out in HazelcastConfigFactory itself; everything else comes from the generated file. */
    private static final List<String> EXPLICIT = List.of(
            "com.siddheshfulawade.payflow.risk.domain.VelocityWindow",
            "com.siddheshfulawade.payflow.risk.infra.VelocityEntryProcessor",
            "java.util.CollSer",
            "java.util.UUID",
            "java.lang.Long",
            "java.lang.Number",
            "java.lang.StackTraceElement",
            "[Ljava.lang.StackTraceElement;",
            "java.util.Collections$EmptyList",
            "com.hazelcast.version.MemberVersion");

    @Test
    @DisplayName("REQ-PF-032 the shipped error file is exactly the jar scan: nothing missing, nothing extra")
    void allowedErrorsFileIsExactlyTheScan() throws Exception {
        var file = HazelcastConfigFactory.allowedErrors(HazelcastConfigFactory.ALLOWED_ERRORS);

        assertThat(file).containsExactlyInAnyOrderElementsOf(hazelcastThrowablesAndBases());
    }

    @Test
    @DisplayName("REQ-PF-032 the allow-list is exactly the explicit entries plus the scan, not one class more")
    void allowListIsExactlyExplicitPlusScan() throws Exception {
        var expected = new TreeSet<String>(EXPLICIT);
        expected.addAll(hazelcastThrowablesAndBases());

        assertThat(filter().getWhitelist().getClasses()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("REQ-PF-032 only Hazelcast errors are opened: other Hazelcast classes and look-alikes stay refused")
    void nothingButHazelcastErrorsIsOpened() throws Exception {
        ClassFilter allowed = filter().getWhitelist();
        // sanity: the filter is the one under test, and it still lets the explicit entries through
        assertThat(allowed.isListed("com.hazelcast.core.HazelcastException")).isTrue();

        assertThat(allowed.isListed("java.util.HashMap")).isFalse();
        assertThat(allowed.isListed("javax.management.BadAttributeValueExpException"))
                .isFalse();
        assertThat(allowed.isListed("com.hazelcastx.core.HazelcastException")).isFalse();
        assertThat(allowed.isListed("com.hazelcast.config.Config")).isFalse();
        assertThat(allowed.isListed("com.hazelcast.map.IMap")).isFalse();
        assertThat(allowed.isListed("com.hazelcast.internal.partition.PartitionReplica"))
                .isFalse();
    }

    private static InternalSerializationService serialization() {
        var properties = new HazelcastProperties("c", 5701, List.of("127.0.0.1:5701"), "127.0.0.1");
        return new DefaultSerializationServiceBuilder()
                .setConfig(HazelcastConfigFactory.build(properties, LIMITS).getSerializationConfig())
                .build();
    }
}
