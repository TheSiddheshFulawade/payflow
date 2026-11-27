package com.siddheshfulawade.payflow.payment.api;

import static com.siddheshfulawade.payflow.payment.support.Tokens.READ;
import static com.siddheshfulawade.payflow.payment.support.Tokens.WRITE;
import static com.siddheshfulawade.payflow.payment.support.Tokens.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.payment.app.PaymentService;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentFixtures;
import com.siddheshfulawade.payflow.payment.support.SecuredWebTest;
import com.siddheshfulawade.payflow.payment.support.TestIssuer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Review findings on REQ-PF-003: unmatched requests are denied, a 401 never explains itself, a token must expire, and
 * the subject rule is the one in contracts.
 */
class SecurityHardeningTest extends SecuredWebTest {

    private static final String GOOD = "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}";
    private static final String BAD_TOKEN = "Bearer error=\"invalid_token\"";

    private ResultActions createWith(String token) throws Exception {
        return mvc.perform(post("/api/v1/payments")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GOOD)
                .header("Authorization", "Bearer " + token));
    }

    private static String challenge(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized());
        return result.andReturn().getResponse().getHeader("WWW-Authenticate");
    }

    private static String token(String issuer, List<String> audience, Instant expiresAt) {
        return TestIssuer.token("user-123", issuer, audience, List.of(WRITE), expiresAt);
    }

    private static MockHttpServletRequestBuilder request(String methodAndPath) {
        String[] parts = methodAndPath.split(" ");
        // PUT and POST carry a body: without Content-Length the length check answers 411 before security runs
        return switch (parts[0]) {
            case "HEAD" -> head(parts[1]);
            case "DELETE" -> delete(parts[1]);
            case "PUT" -> put(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "POST" ->
                post(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
            default -> get(parts[1]);
        };
    }

    // ---- 1: unmatched requests are denied ----

    @Test
    @DisplayName(
            "REQ-PF-003 HEAD on a payment with payments:write only is a 403 problem, not served by the GET handler")
    void headIsDenied() throws Exception {
        mvc.perform(head("/api/v1/payments/" + UUID.randomUUID()).with(as("sub-1", WRITE)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HEAD /api/v1/payments/x",
                "DELETE /api/v1/payments/x",
                "PUT /api/v1/payments",
                "POST /api/v1/payments/x",
                "GET /api/v1/nothing",
                "GET /actuator/info",
                "GET /"
            })
    @DisplayName("REQ-PF-003 a path or method no rule names is 403 for a valid token with every payment role")
    void unmatchedIsDeniedForAValidToken(String methodAndPath) throws Exception {
        mvc.perform(request(methodAndPath).with(as("sub-1", WRITE, READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD /api/v1/payments/x", "DELETE /api/v1/payments/x", "GET /api/v1/nothing"})
    @DisplayName("REQ-PF-003 the same unmatched requests without a token stay 401")
    void unmatchedWithoutTokenIsUnauthorized(String methodAndPath) throws Exception {
        mvc.perform(request(methodAndPath)).andExpect(status().isUnauthorized());
    }

    // ---- 2: the 401 challenge carries no description ----

    @Test
    @DisplayName("REQ-PF-003 no token: WWW-Authenticate is exactly Bearer")
    void noTokenChallengeIsPlainBearer() throws Exception {
        assertThat(challenge(mvc.perform(get("/api/v1/payments/" + UUID.randomUUID()))))
                .isEqualTo("Bearer");
    }

    @Test
    @DisplayName("REQ-PF-003 a non-bearer Authorization header is treated as no token: exactly Bearer")
    void basicAuthorizationChallengeIsPlainBearer() throws Exception {
        assertThat(challenge(mvc.perform(get("/api/v1/payments/" + UUID.randomUUID())
                        .header("Authorization", "Basic YWxpY2U6YWxpY2U="))))
                .isEqualTo("Bearer");
    }

    @Test
    @DisplayName("REQ-PF-003 an expired token: Bearer error=\"invalid_token\" and no error_description")
    void expiredChallenge() throws Exception {
        String expired =
                token(TestIssuer.ISSUER, List.of("payflow-api"), Instant.now().minusSeconds(3_600));

        assertThat(challenge(createWith(expired))).isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName(
            "REQ-PF-003 a token for another audience: invalid_token and no description (it would name the audience)")
    void wrongAudienceChallenge() throws Exception {
        String other = token(
                TestIssuer.ISSUER, List.of("some-other-api"), Instant.now().plusSeconds(300));

        assertThat(challenge(createWith(other))).isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName("REQ-PF-003 a token from another issuer: invalid_token and no description (it would name the issuer)")
    void wrongIssuerChallenge() throws Exception {
        String other = token(
                "http://evil.test/realms/payflow",
                List.of("payflow-api"),
                Instant.now().plusSeconds(300));

        assertThat(challenge(createWith(other))).isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName("REQ-PF-003 a forged token: invalid_token and no description (it would carry the decoder's text)")
    void forgedChallenge() throws Exception {
        assertThat(challenge(createWith(TestIssuer.signedByStranger("user-123", WRITE))))
                .isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName("REQ-PF-003 a malformed token: invalid_token and no description")
    void malformedChallenge() throws Exception {
        assertThat(challenge(createWith("not-a-jwt"))).isEqualTo(BAD_TOKEN);
    }

    // ---- 4: a token must expire ----

    @Test
    @DisplayName("REQ-PF-003 a token without exp is a 401 and never reaches the service")
    void tokenWithoutExpiryIsRefused() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(PaymentFixtures.payment(PaymentState.RESERVING), true));

        assertThat(challenge(createWith(TestIssuer.withoutExpiry("user-123", WRITE))))
                .isEqualTo(BAD_TOKEN);

        verifyNoInteractions(service);
    }

    // ---- 3: the subject rule [\x21-\x7E]{1,64} ----

    @ParameterizedTest
    @ValueSource(strings = {"a\u0085b", "a\u2028b", "a\u202Eb", "a\u200Bb", "\u00e9", "a\u00A0b", "a b"})
    @DisplayName("REQ-PF-003 a subject with a Unicode line break, bidi or zero-width character or non-ASCII is a 401")
    void subjectOutsidePrintableAsciiIsRefused(String subject) throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(PaymentFixtures.payment(PaymentState.RESERVING), true));

        createWith(TestIssuer.valid(subject, WRITE)).andExpect(status().isUnauthorized());

        verify(service, never()).create(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("REQ-PF-003 a subject of 64 printable ASCII characters is accepted and 65 is a 401")
    void subjectLengthBoundary() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(PaymentFixtures.payment(PaymentState.RESERVING), true));
        String sub64 = "!~#%&*+=:;?/|^`{}[]<>(),$-_.@" + "x".repeat(35);

        assertThat(sub64).hasSize(64);
        createWith(TestIssuer.valid(sub64, WRITE)).andExpect(status().isAccepted());
        createWith(TestIssuer.valid(sub64 + "y", WRITE)).andExpect(status().isUnauthorized());
    }
}
