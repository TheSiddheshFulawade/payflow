package com.siddheshfulawade.payflow.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.risk.support.SecuredWebTest;
import com.siddheshfulawade.payflow.risk.support.TestIssuer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * REQ-PF-003 for risk-service: it has no business endpoints, so only /actuator/health may answer without a token, and
 * a token that is wrong in any way is a 401 problem, never an HTML page or an empty body.
 */
class SecurityTest extends SecuredWebTest {

    private static ResultMatcher unauthorizedProblem() {
        return result -> {
            status().isUnauthorized().match(result);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            jsonPath("$.status").value(401).match(result);
            jsonPath("$.title").isNotEmpty().match(result);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Exception")
                    .doesNotContain("com.siddheshfulawade")
                    .doesNotContain("org.springframework")
                    .doesNotContain("\tat ")
                    .doesNotContain("<html");
        };
    }

    @Test
    @DisplayName("REQ-PF-003 GET /actuator/health is open without a token")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/", "/api/v1/payments", "/api/v1/risk", "/actuator", "/actuator/info", "/actuator/metrics"})
    @DisplayName("REQ-PF-003 every other path without a token is a 401 problem, even a path that does not exist")
    void everythingElseIsClosed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 other methods without a token are 401 too (and need no CSRF token to get there)")
    void otherMethodsAreClosed() throws Exception {
        mvc.perform(post("/api/v1/risk")).andExpect(unauthorizedProblem());
        mvc.perform(delete("/api/v1/risk")).andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 a 401 asks for a bearer token")
    void challengeIsBearer() throws Exception {
        mvc.perform(get("/api/v1/risk"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                        .startsWith("Bearer"));
    }

    @Test
    @DisplayName("REQ-PF-003 a garbage bearer token is a 401 problem")
    void garbageToken() throws Exception {
        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 a token for another audience is a 401 problem")
    void wrongAudience() throws Exception {
        String token = TestIssuer.token(
                "user-1",
                TestIssuer.ISSUER,
                List.of("other-api"),
                List.of("ops"),
                Instant.now().plusSeconds(300));

        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + token))
                .andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 a token from another issuer is a 401 problem")
    void wrongIssuer() throws Exception {
        String token = TestIssuer.token(
                "user-1",
                "http://evil.test/realms/payflow",
                List.of("payflow-api"),
                List.of("ops"),
                Instant.now().plusSeconds(300));

        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + token))
                .andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 an expired token is a 401 problem")
    void expired() throws Exception {
        String token = TestIssuer.token(
                "user-1",
                TestIssuer.ISSUER,
                List.of("payflow-api"),
                List.of("ops"),
                Instant.now().minusSeconds(3_600));

        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + token))
                .andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 a token signed with another key, or unsigned, is a 401 problem")
    void forgedOrUnsigned() throws Exception {
        mvc.perform(get("/api/v1/risk")
                        .header("Authorization", "Bearer " + TestIssuer.signedByStranger("user-1", "ops")))
                .andExpect(unauthorizedProblem());
        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + TestIssuer.unsigned("user-1", "ops")))
                .andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 a valid realm token is authenticated but every path is a 403 problem: no endpoint exists")
    void validTokenIsDeniedEverywhere() throws Exception {
        for (String path : new String[] {"/", "/api/v1/risk", "/api/v1/payments", "/actuator", "/actuator/info"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + TestIssuer.valid("user-1", "ops")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
        mvc.perform(post("/api/v1/risk").header("Authorization", "Bearer " + TestIssuer.valid("user-1", "ops")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REQ-PF-003 no token: WWW-Authenticate is exactly Bearer")
    void noTokenChallengeIsPlainBearer() throws Exception {
        mvc.perform(get("/api/v1/risk"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                        .isEqualTo("Bearer"));
    }

    @Test
    @DisplayName(
            "REQ-PF-003 a bad token (expired, other audience, other issuer, forged): invalid_token, no description")
    void badTokenChallengeHasNoDescription() throws Exception {
        List<String> bad = List.of(
                TestIssuer.token(
                        "user-1",
                        TestIssuer.ISSUER,
                        List.of("payflow-api"),
                        List.of("ops"),
                        Instant.now().minusSeconds(3_600)),
                TestIssuer.token(
                        "user-1",
                        TestIssuer.ISSUER,
                        List.of("other-api"),
                        List.of("ops"),
                        Instant.now().plusSeconds(300)),
                TestIssuer.token(
                        "user-1",
                        "http://evil.test/realms/payflow",
                        List.of("payflow-api"),
                        List.of("ops"),
                        Instant.now().plusSeconds(300)),
                TestIssuer.signedByStranger("user-1", "ops"));

        for (String token : bad) {
            mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(result -> assertThat(result.getResponse().getHeader("WWW-Authenticate"))
                            .isEqualTo("Bearer error=\"invalid_token\""));
        }
    }

    @Test
    @DisplayName("REQ-PF-003 a token without exp is a 401 problem")
    void tokenWithoutExpiryIsRefused() throws Exception {
        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + TestIssuer.withoutExpiry("user-1", "ops")))
                .andExpect(unauthorizedProblem());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/v1/payments"
            })
    @DisplayName("REQ-PF-003 the OAuth protected-resource metadata path is refused without a token like any other")
    void protectedResourceMetadataIsClosed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(unauthorizedProblem());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0085b", "a\u2028b", "a\u202Eb", "a\u200Bb", "\u00e9", "a b", "a\nb"})
    @DisplayName("REQ-PF-003 a token whose subject breaks the subject rule (printable ASCII only) is a 401 problem")
    void badSubjectIsRefused(String subject) throws Exception {
        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + TestIssuer.valid(subject, "ops")))
                .andExpect(unauthorizedProblem());
    }

    @Test
    @DisplayName("REQ-PF-003 a subject of 64 printable ASCII characters passes the token check and 65 is a 401")
    void subjectLengthBoundary() throws Exception {
        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + TestIssuer.valid("x".repeat(64), "ops")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risk").header("Authorization", "Bearer " + TestIssuer.valid("x".repeat(65), "ops")))
                .andExpect(unauthorizedProblem());
    }
}
