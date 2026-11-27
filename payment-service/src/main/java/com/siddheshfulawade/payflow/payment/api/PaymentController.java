package com.siddheshfulawade.payflow.payment.api;

import com.siddheshfulawade.payflow.payment.app.ClientIdentity;
import com.siddheshfulawade.payflow.payment.app.PaymentNotFoundException;
import com.siddheshfulawade.payflow.payment.app.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creating and reading payments. The roles are checked in the security filter chain (SecurityConfig). */
@RestController
@RequestMapping(PaymentController.PATH)
class PaymentController {

    static final String PATH = "/api/v1/payments";

    /** Bounded and log-safe; it is stored and compared, never interpreted. */
    private static final String IDEMPOTENCY_KEY = "[A-Za-z0-9._-]{1,64}";

    private final PaymentService payments;
    private final ClientIdentity identity;

    PaymentController(PaymentService payments, ClientIdentity identity) {
        this.payments = payments;
        this.identity = identity;
    }

    /** 202 for a new payment, 200 for a repeated request (REQ-PF-001, 002). */
    @PostMapping
    ResponseEntity<PaymentView> create(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.Created created = payments.create(identity.clientId(), idempotencyKey, request.toNewPayment());
        PaymentView view = PaymentView.of(created.payment());
        if (!created.isNew()) {
            return ResponseEntity.ok(view);
        }
        return ResponseEntity.accepted()
                .location(URI.create(PATH + "/" + view.id()))
                .body(view);
    }

    /**
     * Invariant: a client reads only the payments it created (JWT {@code sub} = {@code client_id}). This is narrower than
     * REQ-PF-003's "payer" rule: payment-service does not know wallet owners, and the wallet's owner check makes the
     * creator the payer wallet's owner for every payment that held money. Someone else's payment is the same 404 as an
     * unknown id, so the response does not tell whether the id exists.
     */
    @GetMapping("/{id}")
    PaymentView read(@PathVariable UUID id) {
        String client = identity.clientId();
        return payments.find(id)
                .filter(payment -> payment.clientId().equals(client))
                .map(PaymentView::of)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
