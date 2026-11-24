package com.siddheshfulawade.payflow.payment.app;

import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The step deadlines come from payflow.payment.deadlines.*; the defaults (10 s, 5 s, 10 s) are checked elsewhere. */
@TestPropertySource(
        properties = {
            "payflow.payment.deadlines.reserve=3s",
            "payflow.payment.deadlines.risk=2s",
            "payflow.payment.deadlines.capture=4s"
        })
class ConfiguredDeadlinesTest extends PaymentIntegrationTest {

    @Test
    @DisplayName("REQ-PF-012 the reserve, risk and capture deadlines follow the configuration")
    void deadlinesAreConfigurable() {
        Instant start = Instant.now();
        Payment p = newPayment();
        assertDeadline(p.id(), Duration.ofSeconds(3), start, Instant.now());

        Instant beforeRisk = Instant.now();
        advanceTo(p.id(), PaymentState.RISK_CHECKING);
        assertDeadline(p.id(), Duration.ofSeconds(2), beforeRisk, Instant.now());

        Instant beforeCapture = Instant.now();
        deliver(reply(new RiskApproved(p.id())));
        assertDeadline(p.id(), Duration.ofSeconds(4), beforeCapture, Instant.now());
    }
}
