package com.siddheshfulawade.payflow.wallet.api;

import static com.siddheshfulawade.payflow.wallet.support.Tokens.OPS;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.READ;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.WRITE;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.siddheshfulawade.payflow.wallet.app.WalletView;
import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import com.siddheshfulawade.payflow.wallet.support.SecuredWebTest;
import com.siddheshfulawade.payflow.wallet.support.TestIssuer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Review findings on REQ-PF-003: unmatched requests are denied, a 401 never explains itself, a token must expire. */
class SecurityHardeningTest extends SecuredWebTest {

    private static final String BAD_TOKEN = "Bearer error=\"invalid_token\"";
    private static final String WALLET = "/api/v1/wallets/w-1";

    private static String challenge(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized());
        return result.andReturn().getResponse().getHeader("WWW-Authenticate");
    }

    private ResultActions readWith(String token) throws Exception {
        return mvc.perform(get(WALLET).header("Authorization", "Bearer " + token));
    }

    private static String token(String issuer, List<String> audience, Instant expiresAt) {
        return TestIssuer.token("user-123", issuer, audience, List.of(READ), expiresAt);
    }

    private static MockHttpServletRequestBuilder request(String methodAndPath) {
        String[] parts = methodAndPath.split(" ");
        // methods with a body carry one, so the answer comes from the security rules and not from the length check
        return switch (parts[0]) {
            case "HEAD" -> head(parts[1]);
            case "DELETE" ->
                delete(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "PUT" -> put(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "POST" ->
                post(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
            default -> get(parts[1]);
        };
    }

    // ---- 1: unmatched requests are denied ----

    @Test
    @DisplayName("REQ-PF-003 HEAD on a wallet by a token with no roles is a 403 problem, not served by the GET handler")
    void headIsDeniedWithoutRoles() throws Exception {
        mvc.perform(head(WALLET).with(as("sub-1"))).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HEAD /api/v1/wallets/w-1",
                "DELETE /api/v1/wallets/w-1",
                "PUT /api/v1/wallets/w-1",
                "POST /api/v1/wallets/w-1",
                "GET /api/v1/wallets",
                "GET /api/v1/nothing",
                "GET /actuator/info",
                "GET /"
            })
    @DisplayName("REQ-PF-003 a path or method no rule names is 403 even for a token with every role")
    void unmatchedIsDeniedForAValidToken(String methodAndPath) throws Exception {
        mvc.perform(request(methodAndPath).with(as("sub-1", OPS, READ, WRITE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD /api/v1/wallets/w-1", "DELETE /api/v1/wallets/w-1", "GET /api/v1/nothing"})
    @DisplayName("REQ-PF-003 the same unmatched requests without a token stay 401")
    void unmatchedWithoutTokenIsUnauthorized(String methodAndPath) throws Exception {
        mvc.perform(request(methodAndPath)).andExpect(status().isUnauthorized());
    }

    // ---- 2: the 401 challenge carries no description ----

    @Test
    @DisplayName("REQ-PF-003 no token: WWW-Authenticate is exactly Bearer")
    void noTokenChallengeIsPlainBearer() throws Exception {
        assertThat(challenge(mvc.perform(get(WALLET)))).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("REQ-PF-003 an expired token: Bearer error=\"invalid_token\" and no error_description")
    void expiredChallenge() throws Exception {
        String expired =
                token(TestIssuer.ISSUER, List.of("payflow-api"), Instant.now().minusSeconds(3_600));

        assertThat(challenge(readWith(expired))).isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName("REQ-PF-003 a token for another audience: invalid_token and no description")
    void wrongAudienceChallenge() throws Exception {
        String other = token(
                TestIssuer.ISSUER, List.of("some-other-api"), Instant.now().plusSeconds(300));

        assertThat(challenge(readWith(other))).isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName("REQ-PF-003 a token from another issuer: invalid_token and no description")
    void wrongIssuerChallenge() throws Exception {
        String other = token(
                "http://evil.test/realms/payflow",
                List.of("payflow-api"),
                Instant.now().plusSeconds(300));

        assertThat(challenge(readWith(other))).isEqualTo(BAD_TOKEN);
    }

    @Test
    @DisplayName("REQ-PF-003 a forged token: invalid_token and no description")
    void forgedChallenge() throws Exception {
        assertThat(challenge(readWith(TestIssuer.signedByStranger("user-123", READ))))
                .isEqualTo(BAD_TOKEN);
    }

    // ---- 4: a token must expire ----

    @Test
    @DisplayName("REQ-PF-003 a token without exp is a 401 and never reaches the service")
    void tokenWithoutExpiryIsRefused() throws Exception {
        assertThat(challenge(readWith(TestIssuer.withoutExpiry("user-123", READ))))
                .isEqualTo(BAD_TOKEN);

        verifyNoInteractions(service);
    }

    // ---- 7: the 404 does not echo the id ----

    @Test
    @DisplayName("REQ-PF-003 foreign and unknown wallets give identical 404 bodies with a fixed instance and no id")
    void notFoundDoesNotEchoTheId() throws Exception {
        when(service.find("w-1"))
                .thenReturn(Optional.of(new WalletView("w-1", "sub-alice", 700, 300, WalletStatus.ACTIVE)));
        when(service.find("unknown-wallet-77")).thenReturn(Optional.empty());

        String foreign = mvc.perform(get(WALLET).with(as("sub-bob", READ)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknown = mvc.perform(get("/api/v1/wallets/unknown-wallet-77").with(as("sub-bob", READ)))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(JsonPath.<String>read(foreign, "$.instance")).isEqualTo("/api/v1/wallets");
        assertThat(foreign).isEqualTo(unknown).doesNotContain("w-1");
        assertThat(unknown).doesNotContain("unknown-wallet-77").doesNotContain("sub-alice");
    }
}
