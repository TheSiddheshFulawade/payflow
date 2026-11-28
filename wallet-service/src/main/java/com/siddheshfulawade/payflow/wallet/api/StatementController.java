package com.siddheshfulawade.payflow.wallet.api;

import com.siddheshfulawade.payflow.wallet.app.StatementLine;
import com.siddheshfulawade.payflow.wallet.app.Statements;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The wallet statement of a UTC business date as CSV, for reconciliation. Ops only (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/ops/statements")
class StatementController {

    static final String HEADER = "payment_id,captured_amount,status\n";

    /** Earliest date asked for: nothing older exists, and an absurd date must not reach the database. */
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    private final Statements statements;

    StatementController(Statements statements) {
        this.statements = statements;
    }

    /**
     * A missing, malformed or out-of-range date is a 400 problem (ApiProblems); nothing is queried. Invariant: the date
     * is from 2000-01-01 to tomorrow (UTC, a day of slack for a caller ahead of us), so a typo cannot ask for a
     * statement of year 9999.
     */
    @GetMapping
    ResponseEntity<String> statement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date.isBefore(EARLIEST)
                || date.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        StringBuilder csv = new StringBuilder(HEADER);
        for (StatementLine line : statements.forDate(date)) {
            // every field is a UUID, a number or one of our own fixed words: nothing needs CSV quoting
            csv.append(line.paymentId())
                    .append(',')
                    .append(line.capturedAmount())
                    .append(',')
                    .append(line.status())
                    .append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }
}
