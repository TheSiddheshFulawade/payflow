package com.siddheshfulawade.payflow.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.payment.app.PaymentService;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentFixtures;
import com.siddheshfulawade.payflow.payment.support.SecuredWebTest;
import com.siddheshfulawade.payflow.payment.support.TestIssuer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Real signed tokens through the real decoder and role mapping: what the realm {@code payflow} issues is accepted, and
 * a token for another audience, issuer or key, an expired one, or an unsigned one is a 401 problem.
 */
class JwtValidationTest extends SecuredWebTest {

    private static final String GOOD = "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}";

    private ResultActions createWith(String token) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/payments")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GOOD);
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private void assertRefusedAsUnauthorized(String token) throws Exception {
        createWith(token)
                .andExpect(status().isUnauthorized())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").isNotEmpty());

        verifyNoInteractions(service);
    }

    private void acceptCreate() {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(PaymentFixtures.payment(PaymentState.RESERVING), true));
    }

    // ---- accepted ----

    @Test
    @DisplayName("REQ-PF-003 a token of the realm with audience payflow-api and role payments:write creates a payment")
    void validToken() throws Exception {
        acceptCreate();

        createWith(TestIssuer.valid("user-123", "payments:write")).andExpect(status().isAccepted());

        verify(service).create(org.mockito.ArgumentMatchers.eq("user-123"), anyString(), any());
    }

    @Test
    @DisplayName("REQ-PF-003 payflow-api among several audiences is enough")
    void audienceMayBeOneOfMany() throws Exception {
        acceptCreate();
        String token = TestIssuer.token(
                "user-123",
                TestIssuer.ISSUER,
                List.of("account", "payflow-api"),
                List.of("payments:write"),
                Instant.now().plusSeconds(300));

        createWith(token).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("REQ-PF-003 realm roles come from realm_access.roles and read access needs payments:read")
    void realmRolesDecideAccess() throws Exception {
        when(service.find(any())).thenReturn(java.util.Optional.empty());
        String writeOnly = TestIssuer.valid("user-123", "payments:write");
        String readOnly = TestIssuer.valid("user-123", "payments:read");
        String id = java.util.UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/payments/" + id).header("Authorization", "Bearer " + writeOnly))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/" + id).header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isNotFound()); // allowed in; there is just no such payment
        createWith(readOnly).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REQ-PF-003 roles in a top-level claim or in the scope do not count, only realm_access.roles")
    void rolesOutsideRealmAccessAreIgnored() throws Exception {
        createWith(TestIssuer.withRolesOutsideRealmAccess("user-123", "payments:write", "ops"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 a token with no realm_access at all is a valid caller with no roles: 403, not 500")
    void noRealmAccessMeansNoRoles() throws Exception {
        String token = TestIssuer.token(
                "user-123",
                TestIssuer.ISSUER,
                List.of("payflow-api"),
                List.of(),
                Instant.now().plusSeconds(300));

        createWith(token).andExpect(status().isForbidden());
    }

    // ---- refused: 401 ----

    @Test
    @DisplayName("REQ-PF-003 a token for another audience is a 401 problem")
    void wrongAudience() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-123",
                TestIssuer.ISSUER,
                List.of("some-other-api"),
                List.of("payments:write"),
                Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("REQ-PF-003 a token with no audience is a 401 problem")
    void missingAudience() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-123",
                TestIssuer.ISSUER,
                List.of(),
                List.of("payments:write"),
                Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("REQ-PF-003 a token from another issuer is a 401 problem, even with the right key and audience")
    void wrongIssuer() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-123",
                "http://evil.test/realms/payflow",
                List.of("payflow-api"),
                List.of("payments:write"),
                Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("REQ-PF-003 an expired token is a 401 problem")
    void expired() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-123",
                TestIssuer.ISSUER,
                List.of("payflow-api"),
                List.of("payments:write"),
                Instant.now().minusSeconds(3_600)));
    }

    @Test
    @DisplayName("REQ-PF-003 a token signed with another key is a 401 problem")
    void forgedSignature() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.signedByStranger("user-123", "payments:write"));
    }

    @Test
    @DisplayName("REQ-PF-003 an unsigned token (alg none) is a 401 problem")
    void unsigned() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.unsigned("user-123", "payments:write"));
    }
}
