package com.siddheshfulawade.payflow.payment.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Failures that happen in the security filter chain, before Spring MVC and its error advice can run, as RFC 9457
 * problems: 401 (no or bad token), 403 (no role) and 400 (the firewall refused the request itself). The body is fixed
 * text with a stable {@code type}; nothing from the exception or the token is echoed to the caller, not in the body
 * and not in the {@code WWW-Authenticate} header (no {@code error_description}, {@code error_uri} or
 * {@code resource_metadata}: they would name the issuer, the audience or the decoder's text). The detail goes to the
 * log at DEBUG only.
 */
final class SecurityProblems implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityProblems.class);

    /**
     * 401. The challenge is exactly {@code Bearer} when no (or no bearer) token was sent, and exactly
     * {@code Bearer error="invalid_token"} when a token was sent and refused (RFC 6750 section 3).
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        log.debug("request refused with 401", e);
        boolean badToken = e instanceof OAuth2AuthenticationException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, badToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized");
    }

    /** 403: the token is valid, but it lacks the role. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden");
    }

    /** 400: for example a NUL byte in a header or a path the firewall does not allow. Never a 500. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException e)
            throws IOException {
        write(response, HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request");
    }

    private static void write(HttpServletResponse response, HttpStatus status, String type, String title)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"type\":\"urn:payflow:problem:" + type + "\",\"title\":\"" + title + "\",\"status\":"
                        + status.value() + "}");
    }
}
