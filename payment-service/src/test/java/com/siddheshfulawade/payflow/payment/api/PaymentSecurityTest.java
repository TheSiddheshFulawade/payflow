package com.siddheshfulawade.payflow.payment.api;

import static com.siddheshfulawade.payflow.payment.support.Tokens.OPS;
import static com.siddheshfulawade.payflow.payment.support.Tokens.READ;
import static com.siddheshfulawade.payflow.payment.support.Tokens.WRITE;
import static com.siddheshfulawade.payflow.payment.support.Tokens.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.siddheshfulawade.payflow.payment.app.NewPayment;
import com.siddheshfulawade.payflow.payment.app.PaymentService;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentFixtures;
import com.siddheshfulawade.payflow.payment.support.SecuredWebTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** REQ-PF-003 on the payment API: who gets in, with which role, and which payments a caller may see. */
class PaymentSecurityTest extends SecuredWebTest {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String GOOD = "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}";

    private static MockHttpServletRequestBuilder create() {
        return post(PAYMENTS)
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(GOOD);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, RequestPostProcessor caller) throws Exception {
        return mvc.perform(request.with(caller));
    }

    /** RFC 9457 body in our words: a status, a title, and nothing about the stack or the libraries. */
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
                    .doesNotContain("stackTrace")
                    .doesNotContain("<html");
        };
    }

    // ---- 401: no or unusable credentials ----

    @Test
    @DisplayName("REQ-PF-003 POST without a token is a 401 problem and never reaches the service")
    void postWithoutToken() throws Exception {
        mvc.perform(create())
                .andExpect(problem(401))
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 GET without a token is a 401 problem")
    void getWithoutToken() throws Exception {
        mvc.perform(get(PAYMENTS + "/" + UUID.randomUUID())).andExpect(problem(401));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-jwt", "Bearer ", "Basic YWxpY2U6YWxpY2U=", "bearer"})
    @DisplayName("REQ-PF-003 a malformed or non-bearer Authorization header is a 401 problem")
    void unusableAuthorization(String header) throws Exception {
        mvc.perform(create().header("Authorization", header)).andExpect(problem(401));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 an invalid body without a token is 401, not 400: the caller learns nothing about the API")
    void authenticationComesBeforeValidation() throws Exception {
        mvc.perform(post(PAYMENTS).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(problem(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/nothing", "/actuator/info", "/actuator/metrics", "/actuator", "/"})
    @DisplayName("REQ-PF-003 every path except /actuator/health asks for a token, even unknown ones")
    void everythingElseIsClosed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(problem(401));
    }

    @Test
    @DisplayName("REQ-PF-003 GET /actuator/health is open without a token")
    void healthIsOpen() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("REQ-PF-003 a bearer-token POST needs no CSRF token")
    void noCsrfForBearerTokens() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(PaymentFixtures.payment(PaymentState.RESERVING), true));

        send(create(), as("sub-1", WRITE)).andExpect(status().isAccepted());
    }

    // ---- 403: a valid token without the role ----

    @Test
    @DisplayName("REQ-PF-003 POST with only payments:read is a 403 problem and never reaches the service")
    void postNeedsWrite() throws Exception {
        send(create(), as("sub-1", READ)).andExpect(problem(403));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 POST by an ops user (no payments:write) is a 403 problem")
    void opsCannotCreate() throws Exception {
        send(create(), as("sub-ops", OPS)).andExpect(problem(403));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 POST by an authenticated caller without any role is a 403 problem")
    void noRoleCannotCreate() throws Exception {
        send(create(), as("sub-1")).andExpect(problem(403));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 GET with only payments:write is a 403 problem and never reaches the service")
    void getNeedsRead() throws Exception {
        send(get(PAYMENTS + "/" + UUID.randomUUID()), as("sub-1", WRITE)).andExpect(problem(403));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 the role names are exact: ROLE_payments:write and a prefix or case change do not count")
    void roleNamesAreExact() throws Exception {
        for (String almost : new String[] {"ROLE_payments:write", "payments:WRITE", "payments", "payments:write "}) {
            send(create(), as("sub-1", almost)).andExpect(problem(403));
        }

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-003 a wrong role is 403 before the body is validated")
    void authorizationComesBeforeValidation() throws Exception {
        send(post(PAYMENTS).contentType(MediaType.APPLICATION_JSON).content("not json"), as("sub-1", READ))
                .andExpect(problem(403));
    }

    // ---- the happy path and the client id ----

    @Test
    @DisplayName("REQ-PF-003 POST with payments:write is 202 and the client id is the token's subject")
    void clientIdIsTheSubject() throws Exception {
        Payment created = PaymentFixtures.paymentOf("sub-alice", PaymentState.RESERVING);
        when(service.create(anyString(), anyString(), any())).thenReturn(new PaymentService.Created(created, true));

        send(create(), as("sub-alice", WRITE)).andExpect(status().isAccepted());

        verify(service).create("sub-alice", "key-1", new NewPayment("alice", "bob", 500, "INR"));
    }

    @Test
    @DisplayName("REQ-PF-002 a header or body field that claims another client changes nothing: the subject is used")
    void clientIdCannotBeClaimed() throws Exception {
        when(service.create(anyString(), anyString(), any()))
                .thenReturn(new PaymentService.Created(PaymentFixtures.payment(PaymentState.RESERVING), true));

        send(create().header("X-Client-Id", "mallory").header("X-Forwarded-User", "mallory"), as("sub-alice", WRITE))
                .andExpect(status().isAccepted());

        verify(service).create(org.mockito.ArgumentMatchers.eq("sub-alice"), anyString(), any());
    }

    // ---- reading: only the creator's own payments ----

    @Test
    @DisplayName("REQ-PF-003 a customer reads the payment they created")
    void readOwnPayment() throws Exception {
        Payment own = PaymentFixtures.paymentOf("sub-alice", PaymentState.COMPLETED);
        when(service.find(own.id())).thenReturn(Optional.of(own));

        send(get(PAYMENTS + "/" + own.id()), as("sub-alice", READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(own.id().toString()))
                .andExpect(jsonPath("$.state").value("COMPLETED"));
    }

    @Test
    @DisplayName(
            "REQ-PF-003 another client's payment is the same 404 problem as an unknown id: existence does not leak")
    void otherClientsPaymentLooksUnknown() throws Exception {
        Payment bobs = PaymentFixtures.paymentOf("sub-bob", PaymentState.COMPLETED);
        UUID unknown = UUID.randomUUID();
        when(service.find(bobs.id())).thenReturn(Optional.of(bobs));
        when(service.find(unknown)).thenReturn(Optional.empty());

        String foreign = send(get(PAYMENTS + "/" + bobs.id()), as("sub-alice", READ, WRITE))
                .andExpect(problem(404))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String missing = send(get(PAYMENTS + "/" + unknown), as("sub-alice", READ, WRITE))
                .andExpect(problem(404))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(foreign).doesNotContain(bobs.id().toString()).doesNotContain("sub-bob");
        assertThat(typeAndTitle(foreign)).isEqualTo(typeAndTitle(missing));
    }

    @Test
    @DisplayName("REQ-PF-003 the owner check compares the whole subject: a prefix or other case is another client")
    void ownerCheckIsExact() throws Exception {
        Payment own = PaymentFixtures.paymentOf("sub-alice", PaymentState.RESERVING);
        when(service.find(own.id())).thenReturn(Optional.of(own));

        send(get(PAYMENTS + "/" + own.id()), as("sub-alic", READ)).andExpect(problem(404));
        send(get(PAYMENTS + "/" + own.id()), as("SUB-ALICE", READ)).andExpect(problem(404));
        send(get(PAYMENTS + "/" + own.id()), as("sub-alice-2", READ)).andExpect(problem(404));
    }

    private static String typeAndTitle(String problemJson) {
        return JsonPath.<String>read(problemJson, "$.type") + "|" + JsonPath.<String>read(problemJson, "$.title");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/v1/payments"
            })
    @DisplayName("REQ-PF-003 the OAuth protected-resource metadata path is refused without a token like any other")
    void protectedResourceMetadataIsClosed(String path) throws Exception {
        mvc.perform(get(path)).andExpect(problem(401));
    }
}
