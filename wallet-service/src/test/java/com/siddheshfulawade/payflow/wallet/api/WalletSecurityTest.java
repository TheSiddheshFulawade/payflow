package com.siddheshfulawade.payflow.wallet.api;

import static com.siddheshfulawade.payflow.wallet.support.Tokens.OPS;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.READ;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.WRITE;
import static com.siddheshfulawade.payflow.wallet.support.Tokens.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.siddheshfulawade.payflow.wallet.app.WalletView;
import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import com.siddheshfulawade.payflow.wallet.support.SecuredWebTest;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** REQ-PF-003 on the wallet API: ops endpoints need ops; a wallet is read by its owner or by ops. */
class WalletSecurityTest extends SecuredWebTest {

    private static final String SEED = "{\"id\":\"w-1\",\"ownerSub\":\"sub-alice\",\"initialBalance\":1000}";

    private static MockHttpServletRequestBuilder seed() {
        return post("/api/v1/ops/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(SEED);
    }

    private static MockHttpServletRequestBuilder freeze() {
        return put("/api/v1/ops/wallets/w-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FROZEN\"}");
    }

    private static ResultMatcher problem(int expectedStatus) {
        return result -> {
            status().is(expectedStatus).match(result);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            jsonPath("$.status").value(expectedStatus).match(result);
            jsonPath("$.title").isNotEmpty().match(result);
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Exception")
                    .doesNotContain("com.siddheshfulawade")
                    .doesNotContain("org.springframework")
                    .doesNotContain("\tat ")
                    .doesNotContain("<html");
        };
    }

    private void walletOf(String owner) {
        when(service.find("w-1")).thenReturn(Optional.of(new WalletView("w-1", owner, 700, 300, WalletStatus.ACTIVE)));
    }

    // ---- 401 ----

    @Test
    @DisplayName("REQ-PF-003 seeding a wallet without a token is a 401 problem and seeds nothing")
    void seedWithoutToken() throws Exception {
        mvc.perform(seed()).andExpect(problem(401));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 changing a wallet status without a token is a 401 problem")
    void statusWithoutToken() throws Exception {
        mvc.perform(freeze()).andExpect(problem(401));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 reading a wallet without a token is a 401 problem")
    void readWithoutToken() throws Exception {
        mvc.perform(get("/api/v1/wallets/w-1")).andExpect(problem(401));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/nothing", "/actuator/info", "/actuator/metrics", "/"})
    @DisplayName("REQ-PF-003 every path except /actuator/health asks for a token, even unknown ones")
    void everythingElseIsClosed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(problem(401));
    }

    @Test
    @DisplayName("REQ-PF-003 GET /actuator/health is open without a token")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---- 403: ops endpoints ----

    @Test
    @DisplayName("REQ-PF-003 seeding needs ops: a customer with payments:write and payments:read gets 403")
    void customerCannotSeed() throws Exception {
        mvc.perform(seed().with(as("sub-alice", WRITE, READ))).andExpect(problem(403));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 changing a wallet status needs ops: a customer gets 403 even for their own wallet")
    void customerCannotFreeze() throws Exception {
        walletOf("sub-alice");

        mvc.perform(freeze().with(as("sub-alice", WRITE, READ))).andExpect(problem(403));

        verify(service, never()).changeStatus(anyString(), any());
    }

    @Test
    @DisplayName("REQ-PF-003 ops can seed and change a status with a bearer token and no CSRF token")
    void opsCanSeedAndFreeze() throws Exception {
        mvc.perform(seed().with(as("sub-ops", OPS))).andExpect(status().isCreated());
        mvc.perform(freeze().with(as("sub-ops", OPS))).andExpect(status().isOk());

        verify(service).seed("w-1", "sub-alice", 1000);
        verify(service).changeStatus("w-1", WalletStatus.FROZEN);
    }

    @Test
    @DisplayName("REQ-PF-003 the ops endpoints are 403 before the body is validated")
    void authorizationComesBeforeValidation() throws Exception {
        mvc.perform(post("/api/v1/ops/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json")
                        .with(as("sub-alice", WRITE, READ)))
                .andExpect(problem(403));
    }

    // ---- reading a wallet ----

    @Test
    @DisplayName("REQ-PF-003 the owner with payments:read reads their wallet")
    void ownerReads() throws Exception {
        walletOf("sub-alice");

        mvc.perform(get("/api/v1/wallets/w-1").with(as("sub-alice", READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("w-1"))
                .andExpect(jsonPath("$.available").value(700));
    }

    @Test
    @DisplayName("REQ-PF-003 reading needs payments:read: the owner with only payments:write gets 403")
    void readNeedsTheRole() throws Exception {
        walletOf("sub-alice");

        mvc.perform(get("/api/v1/wallets/w-1").with(as("sub-alice", WRITE))).andExpect(problem(403));
    }

    @Test
    @DisplayName("REQ-PF-003 someone else's wallet is the same 404 problem as an unknown wallet")
    void foreignWalletLooksUnknown() throws Exception {
        walletOf("sub-alice");
        when(service.find("nope")).thenReturn(Optional.empty());

        String foreign = mvc.perform(get("/api/v1/wallets/w-1").with(as("sub-bob", READ)))
                .andExpect(problem(404))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = mvc.perform(get("/api/v1/wallets/nope").with(as("sub-bob", READ)))
                .andExpect(problem(404))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreign).doesNotContain("sub-alice").doesNotContain("700");
        assertThat(JsonPath.<String>read(foreign, "$.type")).isEqualTo(JsonPath.<String>read(missing, "$.type"));
        assertThat(JsonPath.<String>read(foreign, "$.title")).isEqualTo(JsonPath.<String>read(missing, "$.title"));
    }

    @Test
    @DisplayName("REQ-PF-003 ops can read any wallet")
    void opsReadsAnyWallet() throws Exception {
        walletOf("sub-alice");

        mvc.perform(get("/api/v1/wallets/w-1").with(as("sub-ops", OPS, READ))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("REQ-PF-003 the owner check compares the whole subject, not a prefix or another case")
    void ownerCheckIsExact() throws Exception {
        walletOf("sub-alice");

        mvc.perform(get("/api/v1/wallets/w-1").with(as("sub-alic", READ))).andExpect(problem(404));
        mvc.perform(get("/api/v1/wallets/w-1").with(as("SUB-ALICE", READ))).andExpect(problem(404));
        mvc.perform(get("/api/v1/wallets/w-1").with(as("sub-alice-2", READ))).andExpect(problem(404));
    }
}
