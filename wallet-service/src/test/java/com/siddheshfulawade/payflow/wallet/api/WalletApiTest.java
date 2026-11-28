package com.siddheshfulawade.payflow.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.wallet.app.Statements;
import com.siddheshfulawade.payflow.wallet.app.WalletAdminService;
import com.siddheshfulawade.payflow.wallet.app.WalletAlreadyExistsException;
import com.siddheshfulawade.payflow.wallet.app.WalletNotFoundException;
import com.siddheshfulawade.payflow.wallet.app.WalletView;
import com.siddheshfulawade.payflow.wallet.domain.WalletStatus;
import com.siddheshfulawade.payflow.wallet.support.JwtDefaults;
import java.util.Optional;
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

/** The ops and read endpoints with a mocked service: status codes, validation, and error bodies. */
@WebMvcTest
@Import(JwtDefaults.class)
class WalletApiTest {

    private static final String OPS = "/api/v1/ops/wallets";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WalletAdminService service;

    @MockitoBean
    Statements statements;

    private ResultActions postWallet(String json) throws Exception {
        return mvc.perform(post(OPS).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("REQ-PF-003 ownerSub follows the one subject rule: 64 printable ASCII chars are seeded, 65 are a 400")
    void ownerSubIsAtMost64Chars() throws Exception {
        postWallet("{\"id\":\"w-1\",\"ownerSub\":\"" + "s".repeat(64) + "\",\"initialBalance\":1}")
                .andExpect(status().isCreated());

        postWallet("{\"id\":\"w-2\",\"ownerSub\":\"" + "s".repeat(65) + "\",\"initialBalance\":1}")
                .andExpect(problem(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0085b", "a\u2028b", "a\u202Eb", "a\u200Bb", "\u00e9"})
    @DisplayName("REQ-PF-003 ownerSub with a Unicode line break, bidi or zero-width character or non-ASCII is a 400")
    void ownerSubOutsidePrintableAscii(String ownerSub) throws Exception {
        postWallet("{\"id\":\"w-1\",\"ownerSub\":\"" + ownerSub + "\",\"initialBalance\":1}")
                .andExpect(problem(400));
    }

    private ResultActions putStatus(String id, String json) throws Exception {
        return mvc.perform(put(OPS + "/" + id + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
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

    // ---- POST /api/v1/ops/wallets ----

    @Test
    @DisplayName("REQ-PF-030 seeding a wallet returns 201 with its Location and calls the service once")
    void seedCreated() throws Exception {
        postWallet("{\"id\":\"w-1\",\"ownerSub\":\"user-1\",\"initialBalance\":5000}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/wallets/w-1")));

        verify(service).seed("w-1", "user-1", 5000L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1_000_000_000_000L})
    @DisplayName("REQ-PF-030 initial balance bounds 0 and 1,000,000,000,000 are accepted")
    void balanceBoundsAccepted(long balance) throws Exception {
        postWallet("{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":" + balance + "}")
                .andExpect(status().isCreated());

        verify(service).seed("w-1", "u", balance);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":-1}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":1000000000001}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":10.5}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":100.0}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":\"100\"}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":null}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\"}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":100,\"extra\":1}",
                "{\"id\":\"bad id\",\"ownerSub\":\"u\",\"initialBalance\":100}",
                "{\"id\":\"\",\"ownerSub\":\"u\",\"initialBalance\":100}",
                "{\"id\":\"w/1\",\"ownerSub\":\"u\",\"initialBalance\":100}",
                "{\"ownerSub\":\"u\",\"initialBalance\":100}",
                "{\"id\":\"w-1\",\"initialBalance\":100}",
                "{\"id\":\"w-1\",\"ownerSub\":\"  \",\"initialBalance\":100}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":100",
                "[]",
                ""
            })
    @DisplayName("REQ-PF-004 invalid seed bodies get a 400 problem+json and never reach the service")
    void seedRejectsInvalidBodies(String body) throws Exception {
        postWallet(body).andExpect(problem(400));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a wallet id longer than 64 characters is rejected")
    void seedRejectsLongId() throws Exception {
        String id = "a".repeat(65);
        postWallet("{\"id\":\"" + id + "\",\"ownerSub\":\"u\",\"initialBalance\":1}")
                .andExpect(problem(400));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-030 seeding an existing wallet id returns 409 problem+json")
    void seedDuplicateConflicts() throws Exception {
        doThrow(new WalletAlreadyExistsException("w-1")).when(service).seed(anyString(), anyString(), anyLong());

        postWallet("{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":1}").andExpect(problem(409));
    }

    @Test
    @DisplayName("REQ-PF-004 an unexpected failure is a 500 problem+json that does not leak the cause")
    void unexpectedFailureIsHidden() throws Exception {
        doThrow(new IllegalStateException("jdbc:postgresql://secret-host:5432/wallet password=hunter2"))
                .when(service)
                .seed(anyString(), anyString(), anyLong());

        postWallet("{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":1}")
                .andExpect(problem(500))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("secret-host")
                        .doesNotContain("hunter2"));
    }

    @Test
    @DisplayName("REQ-PF-004 a non-JSON content type gets 415 problem+json")
    void seedRejectsWrongMediaType() throws Exception {
        mvc.perform(post(OPS).contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(problem(415));
    }

    @Test
    @DisplayName("REQ-PF-004 a wrong method on the seed URL is refused by security first: 403 problem+json, not 405")
    void wrongMethodIsProblem() throws Exception {
        mvc.perform(get(OPS)).andExpect(problem(403));
    }

    // ---- PUT /api/v1/ops/wallets/{id}/status ----

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "FROZEN", "CLOSED"})
    @DisplayName("REQ-PF-030 changing a wallet status returns 200 and passes the typed status to the service")
    void statusChanged(String newStatus) throws Exception {
        putStatus("w-1", "{\"status\":\"" + newStatus + "\"}").andExpect(status().isOk());

        verify(service).changeStatus("w-1", WalletStatus.valueOf(newStatus));
    }

    @Test
    @DisplayName("REQ-PF-030 changing the status of an unknown wallet returns 404 problem+json")
    void statusOfUnknownWallet() throws Exception {
        doThrow(new WalletNotFoundException("nope")).when(service).changeStatus(anyString(), any());

        putStatus("nope", "{\"status\":\"FROZEN\"}").andExpect(problem(404));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"status\":\"BOGUS\"}",
                "{\"status\":\"\"}",
                "{\"status\":null}",
                "{\"status\":1}",
                "{}",
                "{\"status\":\"FROZEN\",\"extra\":true}",
                "not json"
            })
    @DisplayName("REQ-PF-004 a bad status body gets a 400 problem+json and never reaches the service")
    void statusRejectsInvalidBodies(String body) throws Exception {
        putStatus("w-1", body).andExpect(problem(400));

        verifyNoInteractions(service);
    }

    // ---- GET /api/v1/wallets/{id} ----

    @Test
    @DisplayName("REQ-PF-030 reading a wallet returns exactly id, ownerSub, available, held and status")
    void readWallet() throws Exception {
        when(service.find("w-1"))
                .thenReturn(Optional.of(new WalletView("w-1", "user-1", 700, 300, WalletStatus.FROZEN)));

        mvc.perform(get("/api/v1/wallets/w-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("w-1"))
                .andExpect(jsonPath("$.ownerSub").value("user-1"))
                .andExpect(jsonPath("$.available").value(700))
                .andExpect(jsonPath("$.held").value(300))
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("REQ-PF-030 reading an unknown wallet returns 404 problem+json")
    void readUnknownWallet() throws Exception {
        when(service.find("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/wallets/nope")).andExpect(problem(404));
    }
}
