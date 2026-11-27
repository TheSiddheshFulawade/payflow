package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain (not system-tagged) guard on {@link ComposeHappyPathSystemTest}: its {@code down -v} must never reach the
 * developer's demo stack, and a hung docker process must not outlive the test (PF-08, REQ-PF-051). Reads the source.
 */
class ComposeProjectIsolationTest {

    private static final Path SOURCE = repoRoot()
            .resolve("system-tests/src/test/java/com/siddheshfulawade/payflow/system/ComposeHappyPathSystemTest.java");

    @Test
    @DisplayName("REQ-PF-051 the system test runs compose under its own project name, never the default payflow")
    void usesItsOwnComposeProject() throws IOException {
        String source = Files.readString(SOURCE);

        assertThat(source).containsPattern("\"-p\"\s*,\s*[A-Z_]*PROJECT[A-Z_]*|\"-p\"\s*,\s*\"payflow-systemtest\"");
        assertThat(source).contains("payflow-systemtest");
        assertThat(source).doesNotContain("\"-p\", \"payflow\"");
        assertThat(source).doesNotContain("COMPOSE_PROJECT_NAME=payflow\"");
    }

    @Test
    @DisplayName("REQ-PF-051 every docker compose command of the system test carries the project flag")
    void everyComposeCommandCarriesTheProject() throws IOException {
        String source = Files.readString(SOURCE);
        String[] parts = source.split("\"docker\", \"compose\"");

        assertThat(parts.length - 1).as("docker compose commands").isGreaterThanOrEqualTo(2);
        for (int i = 1; i < parts.length; i++) {
            assertThat(parts[i].substring(0, Math.min(80, parts[i].length())))
                    .as("command %d", i)
                    .contains("\"-p\"");
        }
    }

    @Test
    @DisplayName("REQ-PF-051 a docker process that exceeds its timeout is destroyed, not left running")
    void processIsDestroyedOnTimeout() throws IOException {
        assertThat(Files.readString(SOURCE)).containsPattern("destroy(Forcibly)?\\(\\)");
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("SPEC.md"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root (folder with SPEC.md) not found");
        }
        return dir;
    }
}
