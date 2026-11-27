package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks the full stack in {@code deploy/compose.yaml} as text, without Docker (PF-08, REQ-PF-051). Services are
 * the two-space-indented keys under {@code services:}.
 */
class ComposeStackTest {

    private static final Path ROOT = repoRoot();
    private static final String ISSUER = "http://localhost:8180/realms/payflow";
    private static final List<String> APPS = List.of("payment-service", "wallet-service", "risk-1", "risk-2");
    private static final List<String> ALL =
            List.of("postgres", "kafka", "keycloak", "payment-service", "wallet-service", "risk-1", "risk-2");
    /** The whole stack must fit a 15 GB laptop with room for the OS, Docker Desktop and an IDE. */
    private static final long BUDGET_MB = 10 * 1024;

    @Test
    @DisplayName(
            "REQ-PF-051 compose declares the three apps and both risk members, built from their module with a named image")
    void declaresAppsWithBuildAndImage() throws IOException {
        Map<String, String> services = services();

        assertThat(services.keySet()).containsAll(ALL);
        Map<String, String> module = Map.of(
                "payment-service", "payment-service",
                "wallet-service", "wallet-service",
                "risk-1", "risk-service",
                "risk-2", "risk-service");
        for (String app : APPS) {
            String block = services.get(app);
            assertThat(block).as(app).containsPattern("(?m)^\\s+context:\\s*\\.\\./" + module.get(app) + "\\s*$");
            assertThat(block)
                    .as(app)
                    .containsPattern("(?m)^\\s+image:\\s*payflow/" + module.get(app) + ":[\\w.-]+\\s*$");
            assertThat(block).as(app).doesNotContain(":latest");
        }
    }

    @Test
    @DisplayName("REQ-PF-051 apps wait for healthy postgres, kafka and keycloak and have their own healthcheck")
    void appsWaitForHealthyDependencies() throws IOException {
        Map<String, String> services = services();

        for (String app : APPS) {
            String block = services.get(app);
            for (String dep : List.of("postgres", "kafka", "keycloak")) {
                assertThat(block)
                        .as("%s depends on healthy %s", app, dep)
                        .containsPattern("(?s)depends_on:.*\\b" + dep + ":\\s*\\n\\s+condition:\\s*service_healthy");
            }
            assertThat(block).as(app).contains("healthcheck:").contains("/actuator/health");
        }
        assertThat(services.get("keycloak")).as("keycloak healthcheck").contains("healthcheck:");
    }

    @Test
    @DisplayName("REQ-PF-051 every service has mem_limit and the sum stays within the documented budget")
    void memoryLimitsFitTheBudget() throws IOException {
        long total = 0;
        for (Map.Entry<String, String> service : services().entrySet()) {
            Matcher m =
                    Pattern.compile("(?m)^\\s+mem_limit:\\s*(\\d+)([mMgG])\\b").matcher(service.getValue());
            assertThat(m.find()).as("mem_limit of %s", service.getKey()).isTrue();
            long value = Long.parseLong(m.group(1));
            total += m.group(2).equalsIgnoreCase("g") ? value * 1024 : value;
        }
        assertThat(total).as("sum of mem_limit in MB").isLessThanOrEqualTo(BUDGET_MB);

        String readme = Files.readString(ROOT.resolve("deploy/README.md"));
        assertThat(readme).as("deploy/README.md states the budget").containsIgnoringCase("memory budget");
    }

    @Test
    @DisplayName("REQ-PF-051 every published port binds 127.0.0.1 and nothing publishes the Hazelcast member port")
    void portsAreLoopbackOnlyAndHazelcastIsNeverPublished() throws IOException {
        Map<String, String> services = services();
        assertThat(services.keySet()).containsAll(APPS);
        for (String app : APPS) {
            assertThat(portsSection(services.get(app)))
                    .as("%s publishes its HTTP port", app)
                    .isNotBlank();
        }
        for (Map.Entry<String, String> service : services.entrySet()) {
            String ports = portsSection(service.getValue());
            Matcher m = Pattern.compile("(?m)^\\s+-\\s*\"?([^\"\\s]+)\"?\\s*$").matcher(ports);
            while (m.find()) {
                assertThat(m.group(1))
                        .as("%s port %s", service.getKey(), m.group(1))
                        .startsWith("127.0.0.1:");
            }
            assertThat(ports)
                    .as("%s ports", service.getKey())
                    .doesNotContain("5701")
                    .doesNotContain("5702");
        }
    }

    @Test
    @DisplayName("REQ-PF-051 the risk members list each other and share a members-only internal network")
    void riskMembersFormAClusterOnAMembersOnlyNetwork() throws IOException {
        String compose = compose();
        Map<String, String> services = services();

        for (String risk : List.of("risk-1", "risk-2")) {
            assertThat(services.get(risk))
                    .as(risk)
                    .containsPattern("RISK_HAZELCAST_MEMBERS:\\s*\"?risk-1:5701,risk-2:5701\"?")
                    .containsPattern("RISK_HAZELCAST_PORT:\\s*\"?5701\"?")
                    .contains("risk-cluster");
        }
        assertThat(compose).containsPattern("(?ms)^networks:.*^\\s+risk-cluster:\\s*\\n\\s+internal:\\s*true");
        for (String other : List.of("postgres", "kafka", "keycloak", "payment-service", "wallet-service")) {
            assertThat(services.get(other))
                    .as("%s is not on the members-only network", other)
                    .doesNotContain("risk-cluster");
        }
    }

    @Test
    @DisplayName("REQ-PF-051 containers bind 0.0.0.0 and reach postgres and kafka by service name")
    void appsAreWiredToPostgresAndKafkaInsideTheNetwork() throws IOException {
        Map<String, String> s = services();

        assertThat(s.get("payment-service"))
                .containsPattern("PAYMENT_BIND:\\s*\"?0\\.0\\.0\\.0\"?")
                .containsPattern("PAYMENT_DB_URL:\\s*\"?jdbc:postgresql://postgres:5432/payment\\b");
        assertThat(s.get("wallet-service"))
                .containsPattern("WALLET_BIND:\\s*\"?0\\.0\\.0\\.0\"?")
                .containsPattern("WALLET_DB_URL:\\s*\"?jdbc:postgresql://postgres:5432/wallet\\b");
        for (String risk : List.of("risk-1", "risk-2")) {
            assertThat(s.get(risk))
                    .containsPattern("RISK_BIND:\\s*\"?0\\.0\\.0\\.0\"?")
                    .containsPattern("RISK_DB_URL:\\s*\"?jdbc:postgresql://postgres:5432/risk\\b");
        }
        for (String app : APPS) {
            assertThat(s.get(app)).as(app).containsPattern("KAFKA_BOOTSTRAP:\\s*\"?kafka:29092\"?");
        }
    }

    @Test
    @DisplayName("REQ-PF-051 every password env value in compose carries the LOCAL DEMO VALUE marker")
    void everyPasswordLineIsMarked() throws IOException {
        List<String> passwordLines = compose()
                .lines()
                .filter(l -> l.toUpperCase().contains("PASSWORD")
                        && l.contains(":")
                        && !l.trim().startsWith("#"))
                .toList();

        assertThat(passwordLines.stream().anyMatch(l -> l.contains("PAYMENT_DB_PASSWORD")))
                .as("app database passwords are set")
                .isTrue();
        assertThat(passwordLines).allSatisfy(l -> assertThat(l).contains("# LOCAL DEMO VALUE"));
    }

    @Test
    @DisplayName("REQ-PF-051 services expect the issuer the demo gets tokens from, and fetch keys inside the network")
    void issuerSeenByServicesEqualsIssuerOfTokensFromTheHost() throws IOException {
        Map<String, String> s = services();

        assertThat(s.get("keycloak")).containsPattern("KC_HOSTNAME:\\s*\"?http://localhost:8180\"?\\s*(#.*)?\\n");
        for (String app : APPS) {
            assertThat(s.get(app))
                    .as(app)
                    .containsPattern("PAYFLOW_ISSUER_URI:\\s*\"?" + Pattern.quote(ISSUER) + "\"?\\s*(#.*)?\\n")
                    // localhost inside a container is the container itself, so discovery from the issuer URL cannot
                    // work
                    .containsPattern("PAYFLOW_JWK_SET_URI:\\s*\"?http://keycloak:8080/realms/payflow"
                            + "/protocol/openid-connect/certs\"?");
        }
        for (String script : List.of("demo.sh", "demo.ps1")) {
            Path file = ROOT.resolve("deploy").resolve(script);
            assertThat(file).isRegularFile();
            assertThat(Files.readString(file)).as(script).contains(ISSUER + "/protocol/openid-connect/token");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment-service", "wallet-service", "risk-service"})
    @DisplayName("REQ-PF-051 production application.yml takes a separate key-set address next to the issuer")
    void applicationYmlTakesJwkSetUriFromEnvironment(String module) throws IOException {
        // Production change implied: Boot's issuer-uri alone does discovery at the issuer URL, which a container
        // cannot reach as localhost. jwk-set-uri makes Boot fetch keys there and still check the issuer claim.
        String yml = Files.readString(ROOT.resolve(module).resolve("src/main/resources/application.yml"));

        assertThat(yml).containsPattern("(?m)^\\s+jwk-set-uri:\\s*\\$\\{PAYFLOW_JWK_SET_URI");
        assertThat(yml).contains("issuer-uri: ${PAYFLOW_ISSUER_URI}");
    }

    private static String portsSection(String block) {
        Matcher m =
                Pattern.compile("(?m)^    ports:\\s*\\n((?:      +-.*\\n?)*)").matcher(block);
        return m.find() ? m.group(1) : "";
    }

    private static String compose() throws IOException {
        Path file = ROOT.resolve("deploy/compose.yaml");
        assertThat(file).isRegularFile();
        return Files.readString(file);
    }

    /** Service name to its text block, in file order. */
    private static Map<String, String> services() throws IOException {
        String compose = compose();
        int start = compose.indexOf("\nservices:");
        assertThat(start).as("services: section").isNotNegative();
        int bodyStart = start + "\nservices:".length();
        Matcher end = Pattern.compile("(?m)^\\S").matcher(compose);
        int stop = compose.length();
        while (end.find(bodyStart)) {
            stop = end.start();
            break;
        }
        String section = compose.substring(bodyStart, stop);
        Matcher m = Pattern.compile("(?m)^  ([\\w-]+):\\s*$").matcher(section);
        Map<String, String> out = new LinkedHashMap<>();
        String name = null;
        int from = 0;
        while (m.find()) {
            if (name != null) {
                out.put(name, section.substring(from, m.start()));
            }
            name = m.group(1);
            from = m.end();
        }
        if (name != null) {
            out.put(name, section.substring(from));
        }
        return out;
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
