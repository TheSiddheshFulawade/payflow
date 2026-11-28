package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain (not system-tagged) guard on {@link SystemStack}, the only place that runs docker compose: its {@code down -v} must never reach the
 * developer's demo stack, and a hung docker process must not outlive the test (PF-08, REQ-PF-051). Reads the source.
 */
class ComposeProjectIsolationTest {

    private static final Path SOURCE =
            repoRoot().resolve("system-tests/src/test/java/com/siddheshfulawade/payflow/system/SystemStack.java");

    @Test
    @DisplayName("REQ-PF-051 the system tests run compose under its own project name, never the default payflow")
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

        assertThat(parts.length - 1).as("docker compose commands").isGreaterThanOrEqualTo(1);
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

    @Test
    @DisplayName("REQ-PF-051 a running demo stack (project payflow) stops the system tests before `up`")
    void runningDemoStackIsRefused() {
        assertThatThrownBy(() -> SystemStack.requireNoDemoStack("3f2a9c\n7b1e04\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("demo stack (project payflow) is running; stop it first: "
                        + "docker compose -f deploy/compose.yaml down");
        assertThatCode(() -> SystemStack.requireNoDemoStack("  \n")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("REQ-PF-051 the demo-stack guard runs before up, and a failed up is rethrown, never ignored")
    void guardRunsBeforeUpAndFailureIsRemembered() throws IOException {
        String source = Files.readString(SOURCE);

        assertThat(source).contains("\"docker\", \"compose\", \"-p\", DEMO_PROJECT, \"-f\", COMPOSE, \"ps\", \"-q\"");
        assertThat(source).contains("DEMO_PROJECT = \"payflow\"");
        assertThat(source.indexOf("requireNoDemoStack(")).isLessThan(source.indexOf("\"up\""));
        assertThat(source).as("failure of the first ensureUp is kept").containsPattern("failure\\s*=");
        assertThat(source)
                .as("and thrown by every later call")
                .containsPattern("throw\\s+failure|throw\\s+\\w*[Ff]ailure");
        // `started = true` before `up` was the fail-open bug: it may only be set after `up` succeeded
        assertThat(source.indexOf("started = true")).isGreaterThan(source.indexOf("\"up\""));
    }

    @Test
    @DisplayName("REQ-PF-051 the demo passwords used by the harness are marked as local demo values")
    void tokenMarkerIsExact() throws IOException {
        Path api = SOURCE.resolveSibling("Api.java");

        assertThat(Files.readString(api))
                .contains("LOCAL DEMO VALUE - each password equals the user name, never reuse anywhere real");
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
