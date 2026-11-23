package com.siddheshfulawade.payflow.wallet.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Refuses bodies over 16 KiB before anything reads them.
 *
 * <p>Invariant: the decision uses headers only, and this filter runs first in the chain, ahead of any filter that
 * reads a body. A write request with any Transfer-Encoding (chunked can be endless) or without Content-Length is
 * 411; the container never reads more than the declared length, and a declared length over the limit is 413.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class BodyLimitFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 16 * 1024L;

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final HandlerExceptionResolver problems;

    BodyLimitFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        boolean writes = METHODS_WITH_BODY.contains(request.getMethod());
        if (writes && (request.getHeader("Transfer-Encoding") != null || length < 0)) {
            refuse(request, response, HttpStatus.LENGTH_REQUIRED);
        } else if (length > MAX_BYTES) {
            refuse(request, response, HttpStatus.CONTENT_TOO_LARGE);
        } else {
            chain.doFilter(request, response);
        }
    }

    /** The same problem format as every other error ({@link ApiProblems}). */
    private void refuse(HttpServletRequest request, HttpServletResponse response, HttpStatus status)
            throws IOException {
        if (problems.resolveException(request, response, null, new ResponseStatusException(status)) == null) {
            response.sendError(status.value());
        }
    }
}
