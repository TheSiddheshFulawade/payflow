package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.wallet.app.WalletAlreadyExistsException;
import com.siddheshfulawade.payflow.wallet.app.WalletNotFoundException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 problem built here: a stable {@code type} and our own words. Text from exceptions (it can
 * quote input, SQL or connection details) goes to the log, never to the caller.
 *
 * <p>It also imports {@link SecurityConfig}: a {@code @WebMvcTest} slice scans controllers and advice but no
 * {@code @Configuration}, so without the import such a test would run with Spring Boot's default security (CSRF on,
 * no problem bodies) instead of what production runs.
 */
@RestControllerAdvice
@Import(SecurityConfig.class)
class ApiProblems extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiProblems.class);

    @ExceptionHandler
    ProblemDetail notFound(WalletNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "wallet-not-found", "Wallet not found");
        // a fixed instance: Spring would fill in the request path, which repeats the id the caller asked for, and a
        // foreign and an unknown wallet must give identical bodies
        problem.setInstance(URI.create("/api/v1/wallets"));
        return problem;
    }

    /** The security firewall checks a header when it is first read, which can be inside the controller call. */
    @ExceptionHandler
    ProblemDetail rejectedByFirewall(RequestRejectedException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request");
    }

    @ExceptionHandler
    ProblemDetail alreadyExists(WalletAlreadyExistsException e) {
        return problem(HttpStatus.CONFLICT, "wallet-already-exists", "Wallet already exists");
    }

    @ExceptionHandler
    ProblemDetail unexpected(Exception e) {
        log.error("unexpected failure of a wallet API call", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error");
    }

    /** Names the invalid fields (names from our own records), not their values. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request");
        problem.setProperty(
                "invalidFields",
                e.getFieldErrors().stream()
                        .map(FieldError::getField)
                        .distinct()
                        .sorted()
                        .toList());
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    /** Spring MVC's own exceptions (unreadable body, 405, 415, ...): keep status and headers, replace the text. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("unexpected failure of a wallet API call", e);
        }
        ProblemDetail problem = switch (status.value()) {
            case 400 -> problem(status, "invalid-request", "Invalid request");
            case 404 -> problem(status, "not-found", "Not found");
            case 405 -> problem(status, "method-not-allowed", "Method not allowed");
            case 406 -> problem(status, "not-acceptable", "Not acceptable");
            case 411 -> problem(status, "length-required", "Content-Length required");
            case 413 -> problem(status, "body-too-large", "Request body too large");
            case 415 -> problem(status, "unsupported-media-type", "Unsupported media type");
            default ->
                status.is4xxClientError()
                        ? problem(status, "invalid-request", "Invalid request")
                        : problem(status, "internal-error", "Internal error");
        };
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static ProblemDetail problem(HttpStatusCode status, String type, String title) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("urn:payflow:problem:" + type));
        problem.setTitle(title);
        return problem;
    }
}
