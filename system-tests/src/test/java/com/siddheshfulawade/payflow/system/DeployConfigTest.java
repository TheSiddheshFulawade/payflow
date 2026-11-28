package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the local-stack files in {@code deploy/} as plain text and JSON, without Docker (PF-01).
 * CI and compose must agree on image tags, and every password must be marked as a demo value.
 */
class DeployConfigTest {

    private static final String POSTGRES_IMAGE = "postgres:18.7-alpine3.24";
    private static final String KAFKA_IMAGE = "apache/kafka:4.4.0";
    private static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.8.2";
    private static final String DEMO_MARKER = "LOCAL DEMO VALUE";

    private static final Path ROOT = repoRoot();
    private static final Path DEPLOY = ROOT.resolve("deploy");

    @Test
    void composeDeclaresPostgresKafkaAndKeycloakWithPinnedImages() throws IOException {
        String compose = read(DEPLOY.resolve("compose.yaml"));

        for (String service : List.of("postgres", "kafka", "keycloak")) {
            assertThat(compose)
                    .as("service %s is declared under services", service)
                    .containsPattern("(?m)^  " + service + ":\s*$");
        }
        assertThat(compose).containsPattern("(?m)^\s+image:\s*" + Pattern.quote(POSTGRES_IMAGE) + "\s*$");
        assertThat(compose).containsPattern("(?m)^\s+image:\s*" + Pattern.quote(KAFKA_IMAGE) + "\s*$");
        assertThat(compose).containsPattern("(?m)^\s+image:\s*" + Pattern.quote(KEYCLOAK_IMAGE) + "\s*$");
        assertThat(compose).as("images are pinned, never :latest").doesNotContain(":latest");
    }

    @Test
    void kafkaRunsInKraftModeWithoutZookeeper() throws IOException {
        String compose = read(DEPLOY.resolve("compose.yaml"));

        assertThat(compose).contains("KAFKA_PROCESS_ROLES");
        assertThat(compose.toLowerCase()).doesNotContain("zookeeper");
    }

    @Test
    void postgresInitScriptCreatesFourDatabasesEachOwnedByItsOwnUser() throws IOException {
        Path initDir = DEPLOY.resolve("postgres/init");
        assertThat(initDir).as("init directory").isDirectory();
        String init;
        try (Stream<Path> files = Files.list(initDir)) {
            List<String> contents = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                contents.add(read(file));
            }
            init = String.join("\n", contents);
        }

        for (String name : List.of("payment", "wallet", "risk", "recon")) {
            assertThat(init)
                    .as("user %s is created", name)
                    .containsPattern("(?is)CREATE\s+(USER|ROLE)\s+\"?" + name + "\"?\\b");
            assertThat(init)
                    .as("database %s is owned by user %s", name, name)
                    .containsPattern("(?is)CREATE\s+DATABASE\s+\"?" + name + "\"?\s+OWNER\s+\"?" + name + "\"?");
        }
    }

    @Test
    void realmImportDefinesRolesTestClientAndUsers() throws IOException {
        JsonNode realm = JsonMapper.builder().build().readTree(read(DEPLOY.resolve("keycloak/payflow-realm.json")));

        assertThat(realm.path("realm").asString()).isEqualTo("payflow");
        assertThat(realm.path("enabled").asBoolean()).isTrue();

        List<String> roles = new ArrayList<>();
        realm.path("roles").path("realm").forEach(r -> roles.add(r.path("name").asString()));
        assertThat(roles).contains("payments:write", "payments:read", "ops");

        JsonNode client = find(realm.path("clients"), "clientId", "payflow-test");
        assertThat(client.isMissingNode()).as("client payflow-test exists").isFalse();
        assertThat(client.path("publicClient").asBoolean()).isTrue();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isTrue();

        assertUser(realm, "alice", "payments:write", "payments:read");
        assertUser(realm, "bob", "payments:write", "payments:read");
        assertUser(realm, "ops", "ops");
    }

    @Test
    void everyDeployFileWithAPasswordIsMarkedAsLocalDemoValue() throws IOException {
        assertThat(read(DEPLOY.resolve("compose.yaml"))).contains(DEMO_MARKER);
        try (Stream<Path> files = Files.list(DEPLOY.resolve("postgres/init"))) {
            String init =
                    String.join("\n", files.map(DeployConfigTest::readUnchecked).toList());
            assertThat(init).contains(DEMO_MARKER);
        }
        // JSON has no comments, so the realm file is documented next to it.
        assertThat(read(DEPLOY.resolve("keycloak/README.md"))).contains(DEMO_MARKER);
    }

    @Test
    void ciPrePullsTheSameImageTagsAsCompose() throws IOException {
        String ci = read(ROOT.resolve(".github/workflows/ci.yml"));
        String compose = read(DEPLOY.resolve("compose.yaml"));

        Matcher pull = Pattern.compile("for image in ([^;]+);\s*do").matcher(ci);
        assertThat(pull.find()).as("pre-pull loop in ci.yml").isTrue();
        List<String> pulled = List.of(pull.group(1).trim().split("\s+"));

        assertThat(pulled).containsExactlyInAnyOrder(POSTGRES_IMAGE, KAFKA_IMAGE);
        for (String image : pulled) {
            assertThat(compose).as("compose uses CI image %s", image).contains("image: " + image);
        }
    }

    @Test
    void composeFixesNoContainerVolumeOrNetworkNamesSoProjectDownVIsolatesTheTestStack() throws IOException {
        String compose = read(DEPLOY.resolve("compose.yaml"));

        assertThat(compose)
                .as("container_name is global, shared with the demo stack")
                .doesNotContain("container_name:");
        // top-level volumes: and networks: blocks (zero indent) up to the next top-level key
        Matcher blocks = Pattern.compile("(?m)^(volumes|networks):\\s*\\R((?:[ \\t].*\\R?|\\s*\\R)*)")
                .matcher(compose);
        int found = 0;
        while (blocks.find()) {
            found++;
            assertThat(blocks.group(2))
                    .as("explicit name: under top-level %s", blocks.group(1))
                    .doesNotContainPattern("(?m)^\\s+name:");
        }
        assertThat(found).as("top-level volumes and networks blocks").isEqualTo(2);
    }

    @Test
    void systemProfileLetsTheForkedJvmFinishItsShutdownHookDownV() throws IOException {
        String pom = read(ROOT.resolve("pom.xml"));
        int profile = pom.indexOf("<id>system</id>");
        assertThat(profile).as("system profile").isPositive();

        assertThat(pom.substring(profile)).containsPattern("<forkedProcessExitTimeoutInSeconds>\\s*150\\s*<");
    }

    private static void assertUser(JsonNode realm, String username, String... expectedRoles) {
        JsonNode user = find(realm.path("users"), "username", username);
        assertThat(user.isMissingNode()).as("user %s exists", username).isFalse();
        assertThat(user.path("enabled").asBoolean()).as("%s enabled", username).isTrue();
        assertThat(user.path("credentials").size())
                .as("%s has credentials", username)
                .isPositive();
        List<String> roles = new ArrayList<>();
        user.path("realmRoles").forEach(r -> roles.add(r.asString()));
        assertThat(roles).as("%s realm roles", username).contains(expectedRoles);
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asString())) {
                return node;
            }
        }
        return array.path(-1);
    }

    private static String read(Path file) throws IOException {
        assertThat(file).as("file %s", ROOT.relativize(file)).isRegularFile();
        return Files.readString(file);
    }

    private static String readUnchecked(Path file) {
        try {
            return read(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Walks up from the working directory (the module dir under Maven) to the folder holding SPEC.md. */
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
