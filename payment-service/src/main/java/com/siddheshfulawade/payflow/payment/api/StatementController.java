package com.siddheshfulawade.payflow.payment.api;

import com.siddheshfulawade.payflow.payment.app.StatementLine;
import com.siddheshfulawade.payflow.payment.app.Statements;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The payment statement of a UTC business date as CSV, for reconciliation. Ops only (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/ops/statements")
class StatementController {

    static final String HEADER = "payment_id,amount,currency,state\n";

    private final Statements statements;

    StatementController(Statements statements) {
        this.statements = statements;
    }

    /** A missing or malformed date is a 400 problem from Spring's own exceptions (ApiProblems); nothing is queried. */
    @GetMapping
    ResponseEntity<String> statement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (StatementLine line : statements.forDate(date)) {
            // every field is a UUID, a number or one of our own fixed words: nothing needs CSV quoting
            csv.append(line.paymentId())
                    .append(',')
                    .append(line.amount())
                    .append(',')
                    .append(line.currency())
                    .append(',')
                    .append(line.state())
                    .append('\n');
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }
}
