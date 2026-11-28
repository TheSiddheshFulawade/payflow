package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Text checks on .github/workflows/ci.yml (SPEC section 7, CI line; REQ-PF-050). Plain unit test, runs everywhere. */
class CiWorkflowTest {

    private static final Path CI = repoRoot().resolve(".github/workflows/ci.yml");

    @Test
    @DisplayName(
            "REQ-PF-050 ci.yml has a system job on ubuntu that packages the jars and runs -Psystem for system-tests")
    void systemJobRunsTheChaosTestsOnUbuntu() throws IOException {
        String job = job("system");

        assertThat(job).as("runs-on").containsPattern("runs-on:\\s*ubuntu-");
        // the Dockerfiles copy target/*.jar: the jars must exist before compose builds the images
        assertThat(job)
                .as("jars built first")
                .containsPattern("mvnw[^\\n]*-DskipTests[^\\n]*package|mvnw[^\\n]*package[^\\n]*-DskipTests");
        assertThat(job).as("chaos tests").containsPattern("mvnw[^\\n]*-Psystem");
        assertThat(job).as("only the system-tests module").containsPattern("-pl\\s+system-tests");
        assertThat(job).as("a bounded run").containsPattern("timeout-minutes:\\s*\\d+");
    }

    @Test
    @DisplayName("REQ-PF-050 the system job runs on every pull request and is not one of the matrix OSes")
    void systemJobIsNotWindowsAndWorkflowRunsOnPullRequests() throws IOException {
        String workflow = Files.readString(CI);

        assertThat(job("system")).doesNotContain("windows");
        assertThat(workflow).containsPattern("(?m)^on:\\s*\\R\\s+pull_request:");
    }

    @Test
    @DisplayName("REQ-PF-050 the system job pre-pulls only the public images, never the locally built risk image")
    void prePullNamesOnlyThePublicServices() throws IOException {
        String job = job("system");

        assertThat(job).containsPattern("compose -f deploy/compose\\.yaml pull postgres kafka keycloak\\b");
        assertThat(job).doesNotContain("--ignore-buildable");
        assertThat(job).doesNotContainPattern("pull[^\\n]*risk");
    }

    /** The text of one top-level job: from "  name:" up to the next job (two-space key) or the end of the file. */
    private static String job(String name) throws IOException {
        String workflow = Files.readString(CI);
        Matcher start =
                Pattern.compile("(?m)^  " + Pattern.quote(name) + ":\\s*$").matcher(workflow);
        assertThat(start.find()).as("job '%s' in ci.yml", name).isTrue();
        Matcher next = Pattern.compile("(?m)^  [A-Za-z0-9_-]+:\\s*$").matcher(workflow);
        int end = next.find(start.end()) ? next.start() : workflow.length();
        return workflow.substring(start.start(), end);
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
