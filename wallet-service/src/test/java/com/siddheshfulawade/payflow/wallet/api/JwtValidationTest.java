package com.siddheshfulawade.payflow.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.wallet.support.SecuredWebTest;
import com.siddheshfulawade.payflow.wallet.support.TestIssuer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Real signed tokens through the real decoder and role mapping: the realm's tokens are accepted, and a token for
 * another audience, issuer or key, an expired one, or an unsigned one is a 401 problem.
 */
class JwtValidationTest extends SecuredWebTest {

    private ResultActions seedWith(String token) throws Exception {
        return mvc.perform(post("/api/v1/ops/wallets")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"w-1\",\"ownerSub\":\"sub-alice\",\"initialBalance\":1000}"));
    }

    private void assertRefusedAsUnauthorized(String token) throws Exception {
        seedWith(token)
                .andExpect(status().isUnauthorized())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").isNotEmpty());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 a realm token with audience payflow-api and the realm role ops can seed a wallet")
    void validOpsToken() throws Exception {
        seedWith(TestIssuer.valid("user-ops", "ops")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("REQ-PF-003 realm roles come from realm_access.roles only: payments:write is not ops")
    void realmRolesDecideAccess() throws Exception {
        seedWith(TestIssuer.valid("user-1", "payments:write", "payments:read")).andExpect(status().isForbidden());
        seedWith(TestIssuer.withRolesOutsideRealmAccess("user-1", "ops")).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 a token for another audience is a 401 problem")
    void wrongAudience() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-ops",
                TestIssuer.ISSUER,
                List.of("other-api"),
                List.of("ops"),
                Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("REQ-PF-003 a token with no audience is a 401 problem")
    void missingAudience() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-ops",
                TestIssuer.ISSUER,
                List.of(),
                List.of("ops"),
                Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("REQ-PF-003 a token from another issuer is a 401 problem, even with the right key and audience")
    void wrongIssuer() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-ops",
                "http://evil.test/realms/payflow",
                List.of("payflow-api"),
                List.of("ops"),
                Instant.now().plusSeconds(300)));
    }

    @Test
    @DisplayName("REQ-PF-003 an expired token is a 401 problem")
    void expired() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.token(
                "user-ops",
                TestIssuer.ISSUER,
                List.of("payflow-api"),
                List.of("ops"),
                Instant.now().minusSeconds(3_600)));
    }

    @Test
    @DisplayName("REQ-PF-003 a token signed with another key is a 401 problem")
    void forgedSignature() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.signedByStranger("user-ops", "ops"));
    }

    @Test
    @DisplayName("REQ-PF-003 an unsigned token (alg none) is a 401 problem")
    void unsigned() throws Exception {
        assertRefusedAsUnauthorized(TestIssuer.unsigned("user-ops", "ops"));
    }
}
