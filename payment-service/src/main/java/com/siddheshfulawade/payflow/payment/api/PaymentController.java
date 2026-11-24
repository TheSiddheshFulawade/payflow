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

/** Creating and reading payments. PF-07 adds the roles and the owner check. */
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

    @GetMapping("/{id}")
    PaymentView read(@PathVariable UUID id) {
        return payments.find(id).map(PaymentView::of).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
