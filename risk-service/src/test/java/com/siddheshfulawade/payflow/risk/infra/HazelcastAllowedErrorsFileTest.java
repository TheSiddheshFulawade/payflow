package com.siddheshfulawade.payflow.risk.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The file of Hazelcast error names is read as exact names, and a missing or empty file stops the start. */
class HazelcastAllowedErrorsFileTest {

    @Test
    @DisplayName("REQ-PF-032 the shipped file yields class names only: no comments, no blanks, no wildcards")
    void shippedFileIsClassNamesOnly() {
        var names = HazelcastConfigFactory.allowedErrors(HazelcastConfigFactory.ALLOWED_ERRORS);

        assertThat(names)
                .contains(
                        "com.hazelcast.internal.partition.PartitionStateVersionMismatchException",
                        "java.lang.Throwable")
                .allSatisfy(name -> assertThat(name).matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+"))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("REQ-PF-032 a missing allow-list file fails the member start, it never starts a member without it")
    void missingFileFailsClosed() {
        assertThatThrownBy(() -> HazelcastConfigFactory.allowedErrors("/no-such-allow-list.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/no-such-allow-list.txt");
    }

    @Test
    @DisplayName("REQ-PF-032 an allow-list file with nothing but comments fails the member start")
    void emptyFileFailsClosed() {
        assertThatThrownBy(() -> HazelcastConfigFactory.allowedErrors("/hazelcast-allowed-errors-comments-only.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    @Test
    @DisplayName("REQ-PF-032 independent of the scan: every listed name is a Throwable, and no Hazelcast entry is"
            + " DataSerializable (a loosened scan can't slip other classes in)")
    void everyEntryIsAThrowableAndNoHazelcastEntryIsDataSerializable() {
        var names = HazelcastConfigFactory.allowedErrors(HazelcastConfigFactory.ALLOWED_ERRORS);

        assertThat(names).allSatisfy(name -> {
            Class<?> type;
            try {
                type = Class.forName(name, false, HazelcastAllowedErrorsFileTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new AssertionError(name + " is not on the classpath", e);
            }
            assertThat(Throwable.class).as(name).isAssignableFrom(type);
            if (name.startsWith("com.hazelcast.")) {
                assertThat(com.hazelcast.nio.serialization.DataSerializable.class.isAssignableFrom(type))
                        .as("%s is DataSerializable, never read by Java serialization", name)
                        .isFalse();
            }
        });
    }
}
