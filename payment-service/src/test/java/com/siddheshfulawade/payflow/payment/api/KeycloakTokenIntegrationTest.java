package com.siddheshfulawade.payflow.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import com.siddheshfulawade.payflow.payment.PaymentServiceApplication;
import com.siddheshfulawade.payflow.payment.support.Containers;
import com.siddheshfulawade.payflow.payment.support.PaymentTestBeans;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The one test against a real Keycloak (REQ-PF-003): the realm export of deploy/keycloak is imported, a token is
 * fetched with the password grant of the test client, and payment-service, configured only with the realm's issuer URL,
 * accepts or refuses it. Everything else about security is tested without Docker (PaymentSecurityTest,
 * JwtValidationTest); this class proves that the realm export and the service agree (audience mapper, realm roles,
 * issuer).
 */
@Tag("it")
@SpringBootTest(classes = {PaymentServiceApplication.class, PaymentTestBeans.class})
@TestPropertySource(properties = "payflow.payment.sweeper.enabled=false")
@AutoConfigureMockMvc
class KeycloakTokenIntegrationTest {

    /** Pinned in deploy/keycloak/payflow-realm.json (local demo value): the subject of alice's tokens never changes. */
    private static final String ALICE_ID = "e45bda66-092a-4922-b8e0-b14c7b03b479";

    private static final Path REALM = Path.of("..", "deploy", "keycloak", "payflow-realm.json");

    @SuppressWarnings("resource") // stopped by Ryuk at exit, like the other containers
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.2")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REALM.toAbsolutePath().normalize()),
                    "/opt/keycloak/data/import/payflow-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/payflow/.well-known/openid-configuration")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void realm(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        registry.add("spring.flyway.locations", () -> "classpath:db/messaging,classpath:db/migration");
        registry.add("payflow.messaging.relay.enabled", () -> "false");
        // only the issuer: the service finds the keys through the realm's discovery document, as in production
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakTokenIntegrationTest::issuer);
    }

    private static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/payflow";
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /** Password grant of the test client: what a script or the demo does to get a token. */
    private static String tokenOf(String user) throws Exception {
        String form = "grant_type=password&client_id=payflow-test&username=" + enc(user) + "&password=" + enc(user);
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(form))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("token endpoint answer for %s", user)
                .isEqualTo(200);
        return JsonPath.read(response.body(), "$.access_token");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder createPayment(String token) {
        MockHttpServletRequestBuilder request = post("/api/v1/payments")
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}");
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("REQ-PF-003 alice's Keycloak token creates a payment (202) and her subject is the client id")
    void aliceCreatesAPayment() throws Exception {
        String token = tokenOf("alice");
        String sub = SignedJWT.parse(token).getJWTClaimsSet().getSubject();
        assertThat(sub).as("alice's id is fixed in the realm export").isEqualTo(ALICE_ID);

        String body = mvc.perform(createPayment(token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RESERVING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String id = JsonPath.read(body, "$.id");
        assertThat(jdbc.queryForObject("select client_id from payment where id = ?::uuid", String.class, id))
                .isEqualTo(ALICE_ID);
        mvc.perform(get("/api/v1/payments/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/" + id).header("Authorization", "Bearer " + tokenOf("bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REQ-PF-003 the same request without a token is a 401 problem")
    void noToken() throws Exception {
        mvc.perform(createPayment(null))
                .andExpect(status().isUnauthorized())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"));
    }

    @Test
    @DisplayName("REQ-PF-003 the ops user's token has no payments:write: POST is a 403 problem and stores nothing")
    void opsCannotCreate() throws Exception {
        long before = jdbc.queryForObject("select count(*) from payment", Long.class);

        mvc.perform(createPayment(tokenOf("ops")))
                .andExpect(status().isForbidden())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"));

        assertThat(jdbc.queryForObject("select count(*) from payment", Long.class))
                .isEqualTo(before);
    }
}
