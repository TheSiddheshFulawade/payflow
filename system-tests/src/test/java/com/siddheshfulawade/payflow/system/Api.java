package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The public HTTP surface of the stack as seen from the host: Keycloak tokens, wallet seeding, payments. */
final class Api {

    static final String WALLETS = "http://localhost:8082/api/v1";
    static final String PAYMENTS = "http://localhost:8081/api/v1/payments";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TOKEN_URL = "http://localhost:8180/realms/payflow/protocol/openid-connect/token";
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // a paused or killed service must show up as a failed request after seconds, not hang the sender
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    // Keycloak's access tokens live for minutes; a new one after 60 s keeps long chaos runs from sending expired ones
    private static final Duration TOKEN_REUSE = Duration.ofSeconds(60);

    private static final Map<String, CachedToken> TOKENS = new ConcurrentHashMap<>();

    private record CachedToken(String value, long fetchedAtNanos) {}

    record Response(int status, String body) {}

    private Api() {}

    /** Realm users of deploy/keycloak.
     * LOCAL DEMO VALUE - each password equals the user name, never reuse anywhere real. */
    static String token(String user) throws Exception {
        CachedToken cached = TOKENS.get(user);
        if (cached != null && System.nanoTime() - cached.fetchedAtNanos() < TOKEN_REUSE.toNanos()) {
            return cached.value();
        }
        String form = "grant_type=password&client_id=payflow-test&username=" + enc(user) + "&password=" + enc(user);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("token for %s", user).isEqualTo(200);
        String token = JSON.readTree(response.body()).path("access_token").asString();
        TOKENS.put(user, new CachedToken(token, System.nanoTime()));
        return token;
    }

    /** The {@code sub} claim: the wallet owner and the payment's client id. */
    static String subject(String jwt) throws IOException {
        byte[] payload = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        return JSON.readTree(new String(payload, StandardCharsets.UTF_8))
                .path("sub")
                .asString();
    }

    /** Seeds a wallet through the ops API. */
    static void seedWallet(String id, String ownerSub, long balance) throws Exception {
        // Invariant: ids are validated ([a-z0-9-]+) before they are put into JSON text
        String body = "{\"id\":\"" + SafeIds.require(id) + "\",\"ownerSub\":\"" + SafeIds.require(ownerSub)
                + "\",\"initialBalance\":" + balance + "}";
        Response response = post(WALLETS + "/ops/wallets", token("ops"), null, body);
        assertThat(response.status()).as("seed %s: %s", id, response.body()).isEqualTo(201);
    }

    /** POST without any status check: the caller decides what a refused or failed request means. */
    static Response post(String url, String token, String idempotencyKey, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    static String paymentBody(String payer, String payee, long amount) {
        // Invariant: wallet ids are validated ([a-z0-9-]+) before they are put into JSON text
        return "{\"payer\":\"" + SafeIds.require(payer) + "\",\"payee\":\"" + SafeIds.require(payee) + "\",\"amount\":"
                + amount
                + ",\"currency\":\"INR\"}";
    }

    static JsonNode get(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", url, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
