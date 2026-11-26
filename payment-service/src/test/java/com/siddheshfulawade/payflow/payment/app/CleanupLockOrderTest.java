package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The test cleanup must wait for a sweep that holds a payment row and then writes the outbox, not deadlock. */
class CleanupLockOrderTest extends PaymentIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("REQ-PF-012 cleanup waits for a sweep-like transaction (payment row, then outbox) without a deadlock")
    void cleanupWaitsForSweepLikeTransaction() throws Exception {
        UUID id = newPayment().id();
        CountDownLatch holdsRow = new CountDownLatch(1);
        CountDownLatch cleanupWaits = new CountDownLatch(1);

        CompletableFuture<Void> sweep = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForList("select id from payment where id = ? for update", id);
                    holdsRow.countDown();
                    await("cleanup is waiting").atMost(TIMEOUT).until(() -> cleanupWaits.getCount() == 0);
                    jdbc.update(
                            "insert into outbox(id, topic, message_key, payload) values (?, 't', 'k', 'p')",
                            UUID.randomUUID());
                }));
        assertThat(holdsRow.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> cleanup = CompletableFuture.runAsync(() -> jdbc.execute(CLEANUP));

        // blocked on a lock, as seen by the database itself
        await().atMost(Duration.ofSeconds(30))
                .until(() -> count("select count(*) from pg_stat_activity "
                                + "where wait_event_type = 'Lock' and query like 'truncate table%'")
                        == 1);
        cleanupWaits.countDown();

        // old order: cleanup holds outbox and waits for payment, the sweep holds payment and waits for outbox
        sweep.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        cleanup.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(paymentRows()).isZero();
    }
}
