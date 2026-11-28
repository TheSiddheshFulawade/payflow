package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the three service Dockerfiles as text, without Docker (PF-08, REQ-PF-051). */
class DockerfileTest {

    private static final Path ROOT = repoRoot();
    private static final Map<String, Integer> PORTS =
            Map.of("payment-service", 8081, "wallet-service", 8082, "risk-service", 8083);
    private static final Pattern PINNED_BASE =
            Pattern.compile("eclipse-temurin:21\\.\\d+(\\.\\d+)*_\\d+-jre(-[a-z0-9.]+)?");

    @Test
    @DisplayName("REQ-PF-051 docs/adr/0001-dependencies.md names the same base image tag as the Dockerfiles")
    void adrNamesTheDockerfileBaseImageTag() throws IOException {
        String adr = Files.readString(ROOT.resolve("docs/adr/0001-dependencies.md"));
        String tag = baseImages("payment-service").get(0).substring("eclipse-temurin:".length());

        assertThat(adr).contains("`eclipse-temurin`").contains("`" + tag + "`");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 every stage uses a pinned eclipse-temurin 21 JRE tag, never latest or a floating 21-jre")
    void baseImageIsPinnedToAnExactTag(String module) throws IOException {
        List<String> images = baseImages(module);

        assertThat(images).as("FROM lines of %s", module).hasSize(2);
        assertThat(images).allSatisfy(image -> assertThat(image).matches(PINNED_BASE));
    }

    @Test
    @DisplayName("REQ-PF-051 all three services share the same base image")
    void allServicesShareOneBaseImage() throws IOException {
        List<String> all = new ArrayList<>();
        for (String module : PORTS.keySet()) {
            all.addAll(baseImages(module));
        }
        assertThat(all).as("all FROM images").hasSize(6).containsOnly(all.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 stage 1 extracts Boot layers from the host-built jar with jarmode tools")
    void extractsLayersFromTheHostBuiltJar(String module) throws IOException {
        String docker = dockerfile(module);

        assertThat(docker).containsPattern("(?m)^COPY\\s+target/\\*\\.jar\\s+\\S+");
        assertThat(docker).contains("-Djarmode=tools").containsPattern("extract\\s+--layers\\s+--launcher");
        assertThat(docker).as("no Maven inside Docker").doesNotContain("mvnw").doesNotContainPattern("(?m)^RUN\\s+mvn");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 stage 2 copies the four layers from least to most changing")
    void copiesLayersInCacheFriendlyOrder(String module) throws IOException {
        String docker = dockerfile(module);
        Matcher m = Pattern.compile("(?m)^COPY\\s+--from=\\S+(\\s+--chown=\\S+)?\\s+\\S*?/?"
                        + "(dependencies|spring-boot-loader|snapshot-dependencies|application)/?\\s")
                .matcher(docker);
        List<String> order = new ArrayList<>();
        while (m.find()) {
            order.add(m.group(2));
        }
        assertThat(order).containsExactly("dependencies", "spring-boot-loader", "snapshot-dependencies", "application");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 the container runs as a numeric non-root user")
    void runsAsNumericNonRootUser(String module) throws IOException {
        List<String> users = lines(dockerfile(module), "USER");

        assertThat(users).as("USER instructions").isNotEmpty();
        String last = users.get(users.size() - 1).split(":")[0];
        assertThat(last).matches("[1-9][0-9]*");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 JVM is sized from the container memory limit and started in exec form")
    void jvmSizedByContainerMemory(String module) throws IOException {
        String docker = dockerfile(module);

        assertThat(docker).containsPattern("-XX:MaxRAMPercentage=\\d+(\\.\\d+)?");
        assertThat(docker).containsPattern("(?m)^ENTRYPOINT\\s+\\[\\s*\"java\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 no ENV or ARG holds a password or secret; the service port is exposed")
    void noSecretsInImageAndPortExposed(String module) throws IOException {
        String docker = dockerfile(module);

        for (String line : lines(docker, "ENV", "ARG")) {
            assertThat(line.toLowerCase()).as(line).doesNotContainPattern("pass|secret|token|key|credential");
        }
        assertThat(docker).containsPattern("(?m)^EXPOSE\\s+" + PORTS.get(module) + "\\s*$");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 .dockerignore keeps the build context to target/*.jar only")
    void dockerignoreKeepsContextSmall(String module) throws IOException {
        Path ignore = ROOT.resolve(module).resolve(".dockerignore");
        assertThat(ignore).as(".dockerignore of %s", module).isRegularFile();
        List<String> rules =
                Files.readAllLines(ignore).stream().map(String::trim).toList();

        assertThat(rules).contains("**").contains("!target/*.jar");
    }

    private static List<String> baseImages(String module) throws IOException {
        List<String> images = new ArrayList<>();
        Matcher m =
                Pattern.compile("(?m)^FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)").matcher(dockerfile(module));
        while (m.find()) {
            images.add(m.group(1));
        }
        return images;
    }

    private static List<String> lines(String docker, String... instructions) {
        List<String> out = new ArrayList<>();
        for (String line : docker.split("\\R")) {
            for (String ins : instructions) {
                if (line.startsWith(ins + " ")) {
                    out.add(line.substring(ins.length()).trim());
                }
            }
        }
        return out;
    }

    private static String dockerfile(String module) throws IOException {
        Path file = ROOT.resolve(module).resolve("Dockerfile");
        assertThat(file).as("Dockerfile of %s", module).isRegularFile();
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
