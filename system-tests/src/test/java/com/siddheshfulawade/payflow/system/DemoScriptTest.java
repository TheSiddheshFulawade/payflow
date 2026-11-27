package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks deploy/demo.ps1 and deploy/demo.sh as text: same steps in both, loopback only (PF-08, REQ-PF-051). */
class DemoScriptTest {

    private static final Path ROOT = repoRoot();
    private static final Path DEPLOY = ROOT.resolve("deploy");

    @ParameterizedTest
    @ValueSource(strings = {"demo.sh", "demo.ps1"})
    @DisplayName(
            "REQ-PF-051 both scripts get a token, seed wallets, send payments with Idempotency-Key and poll to a terminal state")
    void scriptsRunTheSameSteps(String name) throws IOException {
        String script = read(name);

        assertThat(script)
                .contains("http://localhost:8180/realms/payflow/protocol/openid-connect/token")
                .contains("payflow-test")
                .contains("/api/v1/ops/wallets")
                .contains("http://localhost:8082")
                .contains("/api/v1/payments")
                .contains("http://localhost:8081")
                .contains("Idempotency-Key")
                .contains("COMPLETED", "REJECTED", "REVERSED");
        assertThat(script.toLowerCase()).as("polling has a deadline").contains("timeout");
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo.sh", "demo.ps1"})
    @DisplayName("REQ-PF-051 scripts only talk to localhost or 127.0.0.1")
    void onlyLoopbackAddresses(String name) throws IOException {
        Matcher m = Pattern.compile("https?://([^/:\\s\"']+)").matcher(read(name));
        int urls = 0;
        while (m.find()) {
            urls++;
            assertThat(m.group(1)).isIn("localhost", "127.0.0.1");
        }
        assertThat(urls).as("URLs in %s", name).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo.sh", "demo.ps1"})
    @DisplayName(
            "REQ-PF-051 demo credentials are marked LOCAL DEMO VALUE and no long secret-looking literal is embedded")
    void credentialsMarkedAndNoRealLookingSecrets(String name) throws IOException {
        String script = read(name);

        assertThat(script).contains("LOCAL DEMO VALUE");
        // URLs are required (the token endpoint); a secret is a long literal outside them
        String withoutUrls = script.replaceAll("https?://\\S+", "URL");
        assertThat(withoutUrls).as("long token-like literal").doesNotContainPattern("[A-Za-z0-9+/=_]{32,}");
        assertThat(script).doesNotContainPattern("eyJ[A-Za-z0-9_-]{10,}");
    }

    @Test
    @DisplayName("REQ-PF-051 the sh script stops on errors and unset variables and has Unix line endings")
    void shScriptIsStrict() throws IOException {
        String script = read("demo.sh");

        assertThat(script).startsWith("#!");
        assertThat(script).containsPattern("(?m)^set -eu\\b");
        assertThat(script).as("CR breaks a shebang line").doesNotContain("\r");
    }

    @Test
    @DisplayName("REQ-PF-051 the ps1 script stops on the first error")
    void ps1ScriptIsStrict() throws IOException {
        assertThat(read("demo.ps1")).containsPattern("(?i)\\$ErrorActionPreference\\s*=\\s*['\"]Stop['\"]");
    }

    @Test
    @DisplayName("REQ-PF-051 demo.sh passes bash -n (skipped where bash is absent)")
    void shScriptHasValidSyntax() throws Exception {
        read("demo.sh"); // missing file is a failure, not a skip
        assumeTrue(bashWorks(), "bash not available");

        ProcessBuilder pb = new ProcessBuilder("bash", "-n").redirectErrorStream(true);
        pb.redirectInput(DEPLOY.resolve("demo.sh").toFile());
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("bash -n output: %s", output).isZero();
    }

    @Test
    @DisplayName("REQ-PF-051 the READMEs show the compose and demo commands")
    void readmeShowsTheCommands() throws IOException {
        String deploy = Files.readString(DEPLOY.resolve("README.md"));
        String root = Files.readString(ROOT.resolve("README.md"));

        assertThat(deploy)
                .contains("docker compose -f deploy/compose.yaml up")
                .contains("demo.ps1")
                .contains("demo.sh");
        assertThat(root).contains("docker compose -f deploy/compose.yaml up").contains("demo.sh");
    }

    private static boolean bashWorks() {
        try {
            Process p = new ProcessBuilder("bash", "-c", "echo ok")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            return p.waitFor(30, TimeUnit.SECONDS)
                    && p.exitValue() == 0
                    && out.trim().equals("ok");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String read(String name) throws IOException {
        Path file = DEPLOY.resolve(name);
        assertThat(file).as("deploy/%s", name).isRegularFile();
        return Files.readString(file);
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
