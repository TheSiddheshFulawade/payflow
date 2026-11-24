package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** The schedule itself: with it switched on, nobody calls sweep() and an overdue payment is still re-sent. */
@TestPropertySource(properties = {"payflow.payment.sweeper.enabled=true", "payflow.payment.sweeper.interval=200ms"})
// this context keeps sweeping in the background: close it so it cannot touch the next class's rows
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledSweeperTest extends PaymentIntegrationTest {

    @Test
    @DisplayName("REQ-PF-012 the scheduled sweeper re-sends an overdue step by itself, once per deadline")
    void scheduleResendsOverdueSteps() {
        clock.set(Instant.parse("2026-03-01T12:00:00Z"));
        Payment p = newPayment();

        clock.advance(Duration.ofSeconds(11));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(stepAttempts(p.id())).isEqualTo(2));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(stepAttempts(p.id()))
                    .as("not overdue again: deadline is 20 s ahead")
                    .isEqualTo(2);
            assertThat(commands(p.id(), ReserveFunds.class)).hasSize(2);
        });
    }
}
