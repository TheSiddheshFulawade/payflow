package com.siddheshfulawade.payflow.wallet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.wallet.app.WalletAdminService;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import java.io.BufferedReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

/** Body size, strict JSON beyond the basics, ids in the path, and the stable problem types. */
@WebMvcTest
class RequestLimitsTest {

    private static final String OPS = "/api/v1/ops/wallets";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    WalletAdminService service;

    @Test
    @DisplayName("REQ-PF-004 a body over 16 KiB is 413 and never read")
    void bodyTooLarge() throws Exception {
        String padded = "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":1}" + " ".repeat(16 * 1024);

        mvc.perform(post(OPS).contentType(MediaType.APPLICATION_JSON).content(padded))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:body-too-large"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a body without a declared length is 411")
    void lengthRequired() throws Exception {
        mvc.perform(post(OPS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isLengthRequired())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:length-required"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":1,\"initialBalance\":999}",
                "{\"id\":\"w-1\",\"ownerSub\":5,\"initialBalance\":1}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":true}",
                "{\"id\":\"w-1\",\"ownerSub\":\"u\",\"initialBalance\":1e3}",
                "{\"id\":\"w-1\",\"ownerSub\":\"a\\u0000b\",\"initialBalance\":1}"
            })
    @DisplayName("REQ-PF-004 duplicate keys, coerced scalars and control characters are 400")
    void strictBodies(String body) throws Exception {
        mvc.perform(post(OPS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:invalid-request"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a validation problem names the invalid fields")
    void invalidFieldsAreNamed() throws Exception {
        mvc.perform(post(OPS).contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"w-1\",\"ownerSub\":\"u\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidFields[0]").value("initialBalance"));
    }

    @Test
    @DisplayName("REQ-PF-004 an id in the path outside the alphabet is 400 and never reaches the database")
    void invalidPathId() throws Exception {
        mvc.perform(get("/api/v1/wallets/{id}", "bad id")).andExpect(status().isBadRequest());
        mvc.perform(put(OPS + "/{id}/status", "a\u0000b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 an unknown path is a 404 problem")
    void unknownPath() throws Exception {
        mvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:not-found"));
    }

    // ---- the body limit must run before Boot's FormContentFilter, which reads PUT/PATCH/DELETE form bodies ----

    @Autowired
    Environment environment;

    @Autowired
    BodyLimitFilter bodyLimit;

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

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    @DisplayName("REQ-PF-004 a form body over 16 KiB on PUT, PATCH or DELETE is 413 before the body is read")
    void oversizedFormBodyIsRefusedUnread(String method) throws Exception {
        RawRequest[] seen = new RawRequest[1];

        mvc.perform(request(HttpMethod.valueOf(method), OPS + "/w-1/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .with(raw(seen, 16 * 1024 + 1, null)))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:body-too-large"));

        assertThat(seen[0].bodyTouched).as("body read").isFalse();
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("REQ-PF-004 a chunked body (no Content-Length) on any method with a body is 411, unread")
    void chunkedBodyIsLengthRequired(String method) throws Exception {
        RawRequest[] seen = new RawRequest[1];

        mvc.perform(request(HttpMethod.valueOf(method), OPS + "/w-1/status")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .with(raw(seen, -1, "chunked")))
                .andExpect(status().isLengthRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:length-required"));

        assertThat(seen[0].bodyTouched).as("body read").isFalse();
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("REQ-PF-004 a GET without a body is not refused by the body limit")
    void getWithoutBodyIsUnaffected() throws Exception {
        mvc.perform(get("/api/v1/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:not-found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "-a", "_a", "@a"})
    @DisplayName("REQ-PF-004 a wallet id must start with a letter or digit: seeding it is 400")
    void walletIdMustStartAlphanumeric(String id) throws Exception {
        String body = "{\"id\":\"" + id + "\",\"ownerSub\":\"u\",\"initialBalance\":1}";

        mvc.perform(post(OPS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:payflow:problem:invalid-request"))
                .andExpect(jsonPath("$.invalidFields[0]").value("id"));

        verifyNoInteractions(service);
    }
}
