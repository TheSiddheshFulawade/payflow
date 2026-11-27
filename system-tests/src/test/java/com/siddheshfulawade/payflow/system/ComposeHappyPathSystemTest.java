package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole stack from deploy/compose.yaml (built from the Dockerfiles), then token, seed, payment, COMPLETED
 * (PF-08, REQ-PF-051). Needs Docker and `./mvnw package` first; runs only with {@code -Psystem}.
 *
 * <p>Plain JDK for HTTP and `docker compose`; Awaitility does the waiting, never Thread.sleep.
 */
@Tag("system")
class ComposeHappyPathSystemTest {

    private static final Path ROOT = repoRoot();
    // Invariant: own project name, so `down -v` here can never remove the developer's demo stack (project "payflow").
    private static final String PROJECT = "payflow-systemtest";
    private static final String COMPOSE = ROOT.resolve("deploy/compose.yaml").toString();
    private static final String TOKEN_URL = "http://localhost:8180/realms/payflow/protocol/openid-connect/token";
    private static final String WALLETS = "http://localhost:8082/api/v1";
    private static final String PAYMENTS = "http://localhost:8081/api/v1/payments";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final String PAYER =
            "demo-payer-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String PAYEE =
            "demo-payee-" + UUID.randomUUID().toString().substring(0, 8);
    private static final long PAYER_BALANCE = 100_000;
    private static final long PAYEE_BALANCE = 5_000;

    @BeforeAll
    static void startStack() throws Exception {
        run(List.of("docker", "compose", "-p", PROJECT, "-f", COMPOSE, "up", "-d", "--build", "--wait"), 600);
    }

    @AfterAll
    static void stopStack() throws Exception {
        run(List.of("docker", "compose", "-p", PROJECT, "-f", COMPOSE, "down", "-v", "--remove-orphans"), 120);
    }

    @Test
    @DisplayName(
            "REQ-PF-051 compose up, token from the host, seeded wallets, one payment reaches COMPLETED and money is conserved")
    void happyPathThroughTheComposeStack() throws Exception {
        String ops = token("ops");
        String alice = token("alice");
        String aliceSub = subject(alice);
        // the ops token may seed any wallet; owners are the users' fixed Keycloak ids
        post(WALLETS + "/ops/wallets", ops, null, wallet(PAYER, aliceSub, PAYER_BALANCE), 201);
        post(WALLETS + "/ops/wallets", ops, null, wallet(PAYEE, aliceSub, PAYEE_BALANCE), 201);

        String key = "system-" + UUID.randomUUID();
        String body = "{\"payer\":\"" + PAYER + "\",\"payee\":\"" + PAYEE + "\",\"amount\":2500,\"currency\":\"INR\"}";
        HttpResponse<String> created = post(PAYMENTS, alice, key, body, 202);
        String id = JSON.readTree(created.body()).path("id").asString();
        assertThat(id).isNotBlank();

        String[] state = new String[1];
        try {
            await().atMost(Duration.ofMinutes(3))
                    .pollInterval(Duration.ofSeconds(1))
                    // a service still warming up throws: ignored, tried again until the deadline
                    .ignoreExceptions()
                    .until(() -> {
                        state[0] = get(PAYMENTS + "/" + id, alice).path("state").asString();
                        return List.of("COMPLETED", "REJECTED", "REVERSED").contains(state[0]);
                    });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("payment not terminal within 3 minutes, last state " + state[0], e);
        }
        assertThat(state[0]).isEqualTo("COMPLETED");

        assertInvariants(ops);
    }

    /** SPEC section 7, as far as the public API shows it: totals unchanged, nothing held, money moved once. */
    private static void assertInvariants(String ops) throws Exception {
        JsonNode payer = get(WALLETS + "/wallets/" + PAYER, ops);
        JsonNode payee = get(WALLETS + "/wallets/" + PAYEE, ops);

        long total = payer.path("available").asLong()
                + payer.path("held").asLong()
                + payee.path("available").asLong()
                + payee.path("held").asLong();
        assertThat(total).as("sum of available + held is the seeded total").isEqualTo(PAYER_BALANCE + PAYEE_BALANCE);
        assertThat(payer.path("held").asLong() + payee.path("held").asLong())
                .as("sum of held")
                .isZero();
        assertThat(payer.path("available").asLong()).as("payer debited once").isEqualTo(PAYER_BALANCE - 2_500);
        assertThat(payee.path("available").asLong()).as("payee credited once").isEqualTo(PAYEE_BALANCE + 2_500);
    }

    private static String wallet(String id, String owner, long balance) {
        return "{\"id\":\"" + id + "\",\"ownerSub\":\"" + owner + "\",\"initialBalance\":" + balance + "}";
    }

    private static String token(String user) throws Exception {
        String form = "grant_type=password&client_id=payflow-test&username=" + enc(user) + "&password=" + enc(user);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("token for %s", user).isEqualTo(200);
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    private static String subject(String jwt) throws IOException {
        byte[] payload = java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        return JSON.readTree(new String(payload, StandardCharsets.UTF_8))
                .path("sub")
                .asString();
    }

    private static HttpResponse<String> post(String url, String token, String idempotencyKey, String body, int expected)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s: %s", url, response.body())
                .isEqualTo(expected);
        return response;
    }

    private static JsonNode get(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", url, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void run(List<String> command, long timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(ROOT.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly(); // a hung docker process must not outlive the test
        }
        assertThat(finished).as("%s finished", command).isTrue();
        assertThat(process.exitValue()).as("%s exit code", command).isZero();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
