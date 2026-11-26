package com.siddheshfulawade.payflow.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.wallet.support.Tokens;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The ops API against the real database: seeding, reading, freezing, and what freezing does to a reserve. */
class WalletAdminApiTest extends WalletIntegrationTest {

    @Autowired
    MockMvc mvc;

    /** Every call of these tests is made by an ops user who may also read; security has its own tests. */
    private ResultActions asOps(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(Tokens.as("ops-user", Tokens.OPS, Tokens.READ)));
    }

    private void seed(String id, long balance) throws Exception {
        asOps(post("/api/v1/ops/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"ownerSub\":\"sub-" + id + "\",\"initialBalance\":" + balance
                                + "}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/wallets/" + id)));
    }

    @Test
    @DisplayName("REQ-PF-030 seeding creates the wallet, a SEED ledger entry, and the wallet can be read back")
    void seedAndRead() throws Exception {
        seed("alice", 5_000);

        asOps(get("/api/v1/wallets/alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("alice"))
                .andExpect(jsonPath("$.ownerSub").value("sub-alice"))
                .andExpect(jsonPath("$.available").value(5_000))
                .andExpect(jsonPath("$.held").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.length()").value(5));
        assertThat(jdbc.queryForList(
                        "select kind || ' ' || wallet_id || ' ' || amount from ledger_entry where payment_id is null",
                        String.class))
                .containsExactly("SEED alice 5000");
    }

    @Test
    @DisplayName("REQ-PF-030 seeding an existing id is 409 and changes neither balance nor ledger")
    void duplicateSeed() throws Exception {
        seed("alice", 5_000);

        asOps(post("/api/v1/ops/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"alice\",\"ownerSub\":\"someone-else\",\"initialBalance\":99}"))
                .andExpect(status().isConflict())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"));

        assertBalances("alice", 5_000, 0);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select owner_sub from wallet where id = 'alice'", String.class))
                .isEqualTo("sub-alice");
    }

    @Test
    @DisplayName("REQ-PF-030 reading an unknown wallet is 404 problem+json without internals")
    void unknownWallet() throws Exception {
        asOps(get("/api/v1/wallets/nope")).andExpect(status().isNotFound()).andExpect(result -> {
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("Exception")
                    .doesNotContain("com.siddheshfulawade");
        });
    }

    @Test
    @DisplayName("REQ-PF-030 an invalid status is 400, an unknown wallet 404, and the stored status is unchanged")
    void statusErrors() throws Exception {
        seed("alice", 100);

        asOps(put("/api/v1/ops/wallets/alice/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
        asOps(put("/api/v1/ops/wallets/ghost/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select status from wallet where id = 'alice'", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("REQ-PF-030 a frozen wallet is read as FROZEN and the next ReserveFunds is rejected WALLET_FROZEN")
    void freezeBlocksReserve() throws Exception {
        seed("alice", 1_000);
        asOps(put("/api/v1/ops/wallets/alice/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isOk());

        asOps(get("/api/v1/wallets/alice")).andExpect(jsonPath("$.status").value("FROZEN"));
        UUID payment = UUID.randomUUID();
        var reply = apply(reserve(payment, "alice", 100));

        assertThat(reply.payload()).isEqualTo(new FundsRejected(payment, "WALLET_FROZEN"));
        assertBalances("alice", 1_000, 0);
    }

    @Test
    @DisplayName("REQ-PF-030 reads show the held amount after a reserve")
    void readShowsHold() throws Exception {
        seed("alice", 1_000);
        apply(reserve(UUID.randomUUID(), "alice", 400));

        asOps(get("/api/v1/wallets/alice"))
                .andExpect(jsonPath("$.available").value(600))
                .andExpect(jsonPath("$.held").value(400));
    }

    @Test
    @DisplayName("REQ-PF-003 the owner reads their own wallet from the database; another customer gets 404")
    void ownerReadsOnlyTheirOwnWallet() throws Exception {
        seed("alice", 5_000);

        mvc.perform(get("/api/v1/wallets/alice").with(Tokens.as("sub-alice", Tokens.READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(5_000));
        mvc.perform(get("/api/v1/wallets/alice").with(Tokens.as("sub-bob", Tokens.READ)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REQ-PF-003 without a token nothing is seeded: 401, no wallet, no ledger entry")
    void noTokenSeedsNothing() throws Exception {
        mvc.perform(post("/api/v1/ops/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"alice\",\"ownerSub\":\"sub-alice\",\"initialBalance\":5}"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select count(*) from wallet", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry", Integer.class))
                .isZero();
    }
}
