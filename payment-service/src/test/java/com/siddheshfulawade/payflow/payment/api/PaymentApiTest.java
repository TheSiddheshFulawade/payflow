package com.siddheshfulawade.payflow.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.payment.app.ClientIdentity;
import com.siddheshfulawade.payflow.payment.app.IdempotencyKeyReusedException;
import com.siddheshfulawade.payflow.payment.app.NewPayment;
import com.siddheshfulawade.payflow.payment.app.PaymentService;
import com.siddheshfulawade.payflow.payment.app.Statements;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.JwtDefaults;
import com.siddheshfulawade.payflow.payment.support.PaymentFixtures;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/** POST and GET /api/v1/payments with a mocked service: status codes, validation, and error bodies. */
@WebMvcTest
@Import(JwtDefaults.class)
class PaymentApiTest {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String GOOD = "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PaymentService service;

    @MockitoBean
    ClientIdentity identity;

    @MockitoBean
    Statements statements;

    @BeforeEach
    void identityIsLocal() {
        when(identity.clientId()).thenReturn("local");
    }

    private ResultActions postPayment(String key, String json) throws Exception {
        var request = post(PAYMENTS).contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static Payment payment(PaymentState state) {
        return PaymentFixtures.payment(state);
    }

    /** RFC 9457 body that tells the caller what is wrong and nothing about our internals. */
    private static ResultMatcher problem(int expectedStatus) {
        return result -> {
            status().is(expectedStatus).match(result);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.title").isNotEmpty().match(result);
            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .doesNotContain("Exception")
                    .doesNotContain("com.siddheshfulawade")
                    .doesNotContain("tools.jackson")
                    .doesNotContain("com.fasterxml")
                    .doesNotContain("org.springframework")
                    .doesNotContain("\tat ")
                    .doesNotContain("stackTrace");
        };
    }

    // ---- POST: the happy path ----

    @Test
    @DisplayName(
            "REQ-PF-001 a new payment is 202 with Location and its id, and the service gets the client, key and body")
    void createdIsAccepted() throws Exception {
        Payment created = payment(PaymentState.RESERVING);
        when(service.create(anyString(), anyString(), any())).thenReturn(new PaymentService.Created(created, true));

        postPayment("key-1", GOOD)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", endsWith("/api/v1/payments/" + created.id())))
                .andExpect(jsonPath("$.id").value(created.id().toString()))
                .andExpect(jsonPath("$.state").value("RESERVING"));

        verify(service).create("local", "key-1", new NewPayment("alice", "bob", 500, "INR"));
    }

    @Test
    @DisplayName("REQ-PF-002 the same key and body again is 200 with the original payment")
    void repeatIsOk() throws Exception {
        Payment original = payment(PaymentState.CAPTURING);
        when(service.create(anyString(), anyString(), any())).thenReturn(new PaymentService.Created(original, false));

        postPayment("key-1", GOOD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(original.id().toString()))
                .andExpect(jsonPath("$.state").value("CAPTURING"));
    }

    @Test
    @DisplayName("REQ-PF-002 the same key with another body is a 422 problem that does not echo the key")
    void differentBodyIsUnprocessable() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenThrow(new IdempotencyKeyReusedException("secret-key-1"));

        postPayment("secret-key-1", GOOD)
                .andExpect(problem(422))
                .andExpect(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-key-1"));
    }

    @Test
    @DisplayName("REQ-PF-004 an unexpected failure is a 500 problem that does not leak the cause")
    void unexpectedFailureIsHidden() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("jdbc:postgresql://secret-host:5432/payment password=hunter2"));

        postPayment("key-1", GOOD)
                .andExpect(problem(500))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-host")
                        .doesNotContain("hunter2"));
    }

    // ---- POST: the Idempotency-Key header ----

    @Test
    @DisplayName("REQ-PF-004 a missing Idempotency-Key is a 400 problem and never reaches the service")
    void missingKey() throws Exception {
        postPayment(null, GOOD).andExpect(problem(400));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad key", "a/b", "a,b", "café", "k;1", "a\u0000b"})
    @DisplayName("REQ-PF-004 a blank key or one outside [A-Za-z0-9._-] is 400")
    void invalidKey(String key) throws Exception {
        postPayment(key, GOOD).andExpect(problem(400));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a key of 65 characters is 400, and 64 characters of every allowed kind is accepted")
    void keyLength() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(payment(PaymentState.RESERVING), true));
        String longest = "aZ09._-".repeat(9) + "a"; // 64 characters

        postPayment("k".repeat(65), GOOD).andExpect(problem(400));
        postPayment(longest, GOOD).andExpect(status().isAccepted());

        assertThat(longest).hasSize(64);
        verify(service).create("local", longest, new NewPayment("alice", "bob", 500, "INR"));
    }

    // ---- POST: the body ----

    @ParameterizedTest
    @ValueSource(longs = {1, 10_000_000})
    @DisplayName("REQ-PF-004 the amount bounds 1 and 10,000,000 are accepted")
    void amountBoundsAccepted(long amount) throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(payment(PaymentState.RESERVING), true));

        postPayment("key-1", body("alice", "bob", amount, "INR")).andExpect(status().isAccepted());

        verify(service).create("local", "key-1", new NewPayment("alice", "bob", amount, "INR"));
    }

    private static String body(String payer, String payee, long amount, String currency) {
        return "{\"payer\":\"" + payer + "\",\"payee\":\"" + payee + "\",\"amount\":" + amount + ",\"currency\":\""
                + currency + "\"}";
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // amount
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":0,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":-1,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":10000001,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":9223372036854775807,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":1.5,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":100.0,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":1e2,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":\"100\",\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":true,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":null,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"currency\":\"INR\"}",
                // currency
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"USD\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"inr\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR \"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":null}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5}",
                // payer and payee
                "{\"payer\":\"alice\",\"payee\":\"alice\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"bad id\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"b/b\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"  \",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":null,\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\".\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"-bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":5,\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                // shape
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\",\"extra\":1}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"amount\":6,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"",
                "[]",
                "null",
                "not json",
                ""
            })
    @DisplayName("REQ-PF-004 invalid bodies are a 400 problem and never reach the service")
    void invalidBodies(String json) throws Exception {
        postPayment("key-1", json).andExpect(problem(400));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 an account id longer than 64 characters is 400")
    void longAccountId() throws Exception {
        postPayment("key-1", body("a".repeat(65), "bob", 5, "INR")).andExpect(problem(400));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a validation problem names the invalid field and never repeats the rejected value")
    void problemNamesFieldsNotValues() throws Exception {
        postPayment("key-1", body("evil<script>payer", "bob", 5, "INR"))
                .andExpect(problem(400))
                .andExpect(jsonPath("$.invalidFields[0]").value("payer"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("evil")
                        .doesNotContain("script"));
        postPayment(
                        "key-1",
                        "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\",\"leaky-field\":1}")
                .andExpect(problem(400))
                .andExpect(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain("leaky-field"));
        postPayment("key-1", "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":\"leaky-text\",\"currency\":\"INR\"}")
                .andExpect(problem(400))
                .andExpect(result ->
                        assertThat(result.getResponse().getContentAsString()).doesNotContain("leaky-text"));
    }

    @Test
    @DisplayName("REQ-PF-004 a non-JSON content type is 415 problem")
    void wrongMediaType() throws Exception {
        mvc.perform(post(PAYMENTS)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x"))
                .andExpect(problem(415));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 a wrong method on the payments URL is a 403 problem: unmatched requests are denied")
    void wrongMethod() throws Exception {
        mvc.perform(delete(PAYMENTS)).andExpect(problem(403));
    }

    // ---- GET /api/v1/payments/{id} ----

    @Test
    @DisplayName("REQ-PF-010 reading a payment returns exactly id, payer, payee, amount, currency, state and the times")
    void readPayment() throws Exception {
        Payment payment = payment(PaymentState.COMPLETED);
        when(service.find(payment.id())).thenReturn(Optional.of(payment));

        mvc.perform(get(PAYMENTS + "/" + payment.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.id().toString()))
                .andExpect(jsonPath("$.payer").value("alice"))
                .andExpect(jsonPath("$.payee").value("bob"))
                .andExpect(jsonPath("$.amount").value(2500))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.clientId").doesNotExist())
                .andExpect(jsonPath("$.requestHash").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("REQ-PF-010 reading an unknown payment is a 404 problem")
    void readUnknownPayment() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.find(id)).thenReturn(Optional.empty());

        mvc.perform(get(PAYMENTS + "/" + id)).andExpect(problem(404));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1234", "00000000-0000-0000-0000-00000000000g", "%20"})
    @DisplayName("REQ-PF-004 a malformed payment id is a 400 problem and never reaches the service")
    void malformedId(String id) throws Exception {
        mvc.perform(get(PAYMENTS + "/" + id)).andExpect(problem(400));

        verifyNoInteractions(service);
    }
}
