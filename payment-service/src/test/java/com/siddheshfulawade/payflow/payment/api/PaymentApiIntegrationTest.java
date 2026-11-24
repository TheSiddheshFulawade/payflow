package com.siddheshfulawade.payflow.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The REST API on the whole stack: real service, real database, real outbox. */
class PaymentApiIntegrationTest extends PaymentIntegrationTest {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String GOOD = "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}";

    private MockHttpServletRequestBuilder postPayment(String key, String json) {
        return post(PAYMENTS)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private UUID idOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return UUID.fromString(body.replaceAll("(?s).*\"id\"\\s*:\\s*\"([0-9a-f-]{36})\".*", "$1"));
    }

    @Test
    @DisplayName(
            "REQ-PF-001 POST creates the payment: 202, Location, id and state in the body, one ReserveFunds queued")
    void postCreates() throws Exception {
        MvcResult result = mvc.perform(postPayment(freshKey(), GOOD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RESERVING"))
                .andReturn();

        UUID id = idOf(result);
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/v1/payments/" + id);
        assertThat(state(id)).isEqualTo("RESERVING");
        assertThat(outboxCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-002 the same key and body is 200 with the original id and creates nothing")
    void repeatIsOk() throws Exception {
        String key = freshKey();
        UUID id = idOf(mvc.perform(postPayment(key, GOOD)).andReturn());
        Snapshot before = snapshotAll();

        mvc.perform(postPayment(key, GOOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        assertThat(snapshotAll()).isEqualTo(before);
    }

    @Test
    @DisplayName("REQ-PF-002 JSON whitespace and field order do not make a different body")
    void sameFieldsInAnotherLayoutIsTheSameBody() throws Exception {
        String key = freshKey();
        UUID id = idOf(mvc.perform(postPayment(key, GOOD)).andReturn());
        String shuffled = "{ \"currency\" : \"INR\",\n  \"amount\" : 500,\n \"payee\":\"bob\", \"payer\":\"alice\" }";

        mvc.perform(postPayment(key, shuffled))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-002 the same key with a different amount is 422 and changes nothing")
    void differentBodyIs422() throws Exception {
        String key = freshKey();
        mvc.perform(postPayment(key, GOOD)).andExpect(status().isAccepted());
        Snapshot before = snapshotAll();

        mvc.perform(postPayment(key, GOOD.replace("500", "501")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(result ->
                        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json"));

        assertThat(snapshotAll()).isEqualTo(before);
    }

    @Test
    @DisplayName("REQ-PF-002 the client is fixed to 'local': a spoofed X-Client-Id header changes nothing")
    void clientIdentityIgnoresHeaders() throws Exception {
        String key = freshKey();
        UUID id = idOf(mvc.perform(postPayment(key, GOOD).header("X-Client-Id", "mallory"))
                .andExpect(status().isAccepted())
                .andReturn());

        assertThat(jdbc.queryForObject("select client_id from payment where id = ?", String.class, id))
                .isEqualTo("local");
        mvc.perform(postPayment(key, GOOD).header("X-Client-Id", "someone-else"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(postPayment(key, GOOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-004 an invalid request is a 400 problem and leaves no payment, event or message")
    void invalidRequestStoresNothing() throws Exception {
        mvc.perform(post(PAYMENTS).contentType(MediaType.APPLICATION_JSON).content(GOOD))
                .andExpect(status().isBadRequest());
        mvc.perform(postPayment(freshKey(), GOOD.replace("500", "0"))).andExpect(status().isBadRequest());
        mvc.perform(postPayment(freshKey(), GOOD.replace("\"bob\"", "\"alice\"")))
                .andExpect(status().isBadRequest());

        assertThat(paymentRows()).isZero();
        assertThat(count("select count(*) from payment_event")).isZero();
        assertThat(count("select count(*) from outbox")).isZero();
    }

    @Test
    @DisplayName("REQ-PF-004 a database failure is a 500 problem without the cause, and stores nothing")
    void failureIsHidden() throws Exception {
        makeOutboxInsertFail("wallet.commands");

        mvc.perform(postPayment(freshKey(), GOOD))
                .andExpect(status().isInternalServerError())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("injected")
                        .doesNotContain("outbox")
                        .doesNotContain("pf_outbox_fail")
                        .doesNotContain("org.postgresql"));

        assertThat(paymentRows()).isZero();
    }

    @Test
    @DisplayName("REQ-PF-010 GET shows the payment's current state and only the public fields")
    void getShowsCurrentState() throws Exception {
        UUID id = idOf(mvc.perform(postPayment(freshKey(), GOOD)).andReturn());
        deliver(reply(new FundsReserved(id)));

        mvc.perform(get(PAYMENTS + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RISK_CHECKING"))
                .andExpect(jsonPath("$.payer").value("alice"))
                .andExpect(jsonPath("$.payee").value("bob"))
                .andExpect(jsonPath("$.amount").value(500))
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.clientId").doesNotExist());
    }

    @Test
    @DisplayName("REQ-PF-010 GET of an unknown id is 404 and of a malformed id is 400")
    void getUnknownAndMalformed() throws Exception {
        mvc.perform(get(PAYMENTS + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get(PAYMENTS + "/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
