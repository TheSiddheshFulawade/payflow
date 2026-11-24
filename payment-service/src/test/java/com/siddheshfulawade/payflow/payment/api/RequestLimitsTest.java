package com.siddheshfulawade.payflow.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.payment.app.ClientIdentity;
import com.siddheshfulawade.payflow.payment.app.PaymentService;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import java.io.BufferedReader;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Body size, strict JSON beyond the basics, and the stable problem types. */
@WebMvcTest
class RequestLimitsTest {

    private static final String PAYMENTS = "/api/v1/payments";
    private static final String GOOD = "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":500,\"currency\":\"INR\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    Environment environment;

    @Autowired
    BodyLimitFilter bodyLimit;

    @MockitoBean
    PaymentService service;

    @MockitoBean
    ClientIdentity identity;

    @Test
    @DisplayName("REQ-PF-004 a body over 16 KiB is 413 and never read")
    void bodyTooLarge() throws Exception {
        String padded = GOOD + " ".repeat(16 * 1024);

        mvc.perform(post(PAYMENTS)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(padded))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:body-too-large"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a body without a declared length is 411")
    void lengthRequired() throws Exception {
        mvc.perform(post(PAYMENTS).header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isLengthRequired())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:length-required"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":5,\"amount\":999,\"currency\":\"INR\"}",
                "{\"payer\":5,\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":true,\"currency\":\"INR\"}",
                "{\"payer\":\"alice\",\"payee\":\"bob\",\"amount\":1e3,\"currency\":\"INR\"}",
                "{\"payer\":\"a\\u0000b\",\"payee\":\"bob\",\"amount\":5,\"currency\":\"INR\"}"
            })
    @DisplayName("REQ-PF-004 duplicate keys, coerced scalars and control characters are 400 invalid-request")
    void strictBodies(String body) throws Exception {
        mvc.perform(post(PAYMENTS)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:invalid-request"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a validation problem lists the invalid field names")
    void invalidFieldsAreNamed() throws Exception {
        mvc.perform(post(PAYMENTS)
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payer\":\"alice\",\"payee\":\"bob\",\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:invalid-request"))
                .andExpect(jsonPath("$.invalidFields[0]").value("amount"));
    }

    @Test
    @DisplayName("REQ-PF-004 an unknown path is a 404 problem")
    void unknownPath() throws Exception {
        mvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:not-found"));
    }

    @Test
    @DisplayName("REQ-PF-004 Boot's form content filter is off and the body limit is first in the chain")
    void bodyLimitIsFirst() {
        assertThat(environment.getProperty("spring.mvc.formcontent.filter.enabled", Boolean.class))
                .isFalse();
        Ordered highest = () -> Ordered.HIGHEST_PRECEDENCE;
        assertThat(AnnotationAwareOrderComparator.INSTANCE.compare(bodyLimit, highest))
                .as("order of the body limit against HIGHEST_PRECEDENCE")
                .isZero();
    }

    // ---- Transfer-Encoding and DELETE: decided on headers alone, before anything reads the body ----

    /** A request that declares its own length (or none) and notes whether anyone touched the body. */
    private static final class RawRequest extends MockHttpServletRequest {
        private final long declaredLength;
        boolean bodyTouched;

        RawRequest(ServletContext context, String method, String uri, long declaredLength) {
            super(context, method, uri);
            this.declaredLength = declaredLength;
        }

        @Override
        public long getContentLengthLong() {
            return declaredLength;
        }

        @Override
        public int getContentLength() {
            return (int) declaredLength;
        }

        @Override
        public ServletInputStream getInputStream() {
            bodyTouched = true;
            throw new AssertionError("the body must not be read");
        }

        @Override
        public BufferedReader getReader() {
            bodyTouched = true;
            throw new AssertionError("the body must not be read");
        }
    }

    private static RequestPostProcessor raw(RawRequest[] seen, long declaredLength, String transferEncoding) {
        return original -> {
            RawRequest request = new RawRequest(
                    original.getServletContext(), original.getMethod(), original.getRequestURI(), declaredLength);
            request.setContentType(original.getContentType());
            if (transferEncoding != null) {
                request.addHeader("Transfer-Encoding", transferEncoding);
            }
            seen[0] = request;
            return request;
        };
    }

    @ParameterizedTest
    @CsvSource({
        "POST,-1",
        "POST,10",
        "GET,-1",
        "GET,10",
        "DELETE,-1",
        "DELETE,10",
        "PUT,-1",
        "PUT,10",
    })
    @DisplayName("REQ-PF-004 any Transfer-Encoding is 411 on any method, with or without Content-Length, body unread")
    void transferEncodingIsLengthRequired(String method, long declaredLength) throws Exception {
        RawRequest[] seen = new RawRequest[1];

        mvc.perform(request(HttpMethod.valueOf(method), PAYMENTS + "/" + UUID.randomUUID())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(raw(seen, declaredLength, "chunked")))
                .andExpect(status().isLengthRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:length-required"));

        assertThat(seen[0].bodyTouched).as("body read").isFalse();
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a Transfer-Encoding other than chunked (identity, gzip, chunked listed twice) is 411 too")
    void anyTransferEncodingValueIsRefused() throws Exception {
        for (String value : new String[] {"identity", "gzip, chunked", "chunked, chunked"}) {
            RawRequest[] seen = new RawRequest[1];

            mvc.perform(post(PAYMENTS)
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(raw(seen, 10, value)))
                    .andExpect(status().isLengthRequired());

            assertThat(seen[0].bodyTouched).as("body read for %s", value).isFalse();
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a DELETE declaring more than 16 KiB is 413 before its body is read")
    void deleteWithOversizedDeclaredBodyIsTooLarge() throws Exception {
        RawRequest[] seen = new RawRequest[1];

        mvc.perform(request(HttpMethod.DELETE, PAYMENTS + "/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .with(raw(seen, 16 * 1024 + 1, null)))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:body-too-large"));

        assertThat(seen[0].bodyTouched).as("body read").isFalse();
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a plain DELETE without any length is not 411 (no body can follow on HTTP/1.1): it is 405")
    void plainDeleteIsNotLengthRequired() throws Exception {
        RawRequest[] seen = new RawRequest[1];

        mvc.perform(request(HttpMethod.DELETE, PAYMENTS + "/" + UUID.randomUUID())
                        .with(raw(seen, -1, null)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:method-not-allowed"));
    }

    // ---- multipart: switched off, so a malformed boundary cannot fail inside the container's parser ----

    @Test
    @DisplayName("REQ-PF-004 multipart/form-data without a boundary is 415 and the service is not called")
    void multipartWithoutBoundaryIsUnsupported() throws Exception {
        mvc.perform(post(PAYMENTS)
                        .header("Idempotency-Key", "key-1")
                        .contentType("multipart/form-data")
                        .content("--x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:unsupported-media-type"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a GET without a body is not refused by the body limit")
    void getWithoutBodyIsUnaffected() throws Exception {
        mvc.perform(get("/api/v1/nothing")).andExpect(status().isNotFound());
    }
}
