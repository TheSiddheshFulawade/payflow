package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Many threads at one wallet, through the idempotent consumer, like several service instances would be. */
class WalletConcurrencyTest extends WalletIntegrationTest {

    private Callable<Envelope> run(Envelope command) {
        return () -> {
            consumer.handle(command, handler::handle);
            return storedReply(command);
        };
    }

    @Test
    @DisplayName("REQ-PF-030 50 concurrent reserves of 30 on 1,000: exactly 33 succeed, 17 are INSUFFICIENT_FUNDS")
    void concurrentReservesNeverOverspend() throws Exception {
        wallet("alice", 1_000);
        List<Callable<Envelope>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(run(reserve(UUID.randomUUID(), "alice", 30)));
        }

        List<Envelope> replies = concurrently(tasks, TIMEOUT);

        assertThat(replies.stream().filter(r -> r.payload() instanceof FundsReserved))
                .as("FundsReserved")
                .hasSize(33);
        assertThat(replies.stream()
                        .map(Envelope::payload)
                        .filter(p -> p instanceof FundsRejected rejected
                                && rejected.reason().equals("INSUFFICIENT_FUNDS")))
                .as("INSUFFICIENT_FUNDS")
                .hasSize(17);
        assertBalances("alice", 10, 990);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where kind = 'RESERVE'", Integer.class))
                .isEqualTo(33);
    }

    @Test
    @DisplayName("REQ-PF-021 two threads reserving the same payment with different message ids hold once")
    void samePaymentTwoThreadsHoldsOnce() throws Exception {
        wallet("alice", 1_000_000);
        int rounds = 30;
        for (int i = 0; i < rounds; i++) {
            UUID payment = UUID.randomUUID();
            Envelope first = reserve(payment, "alice", 100);

            List<Envelope> replies = concurrently(List.of(run(first), run(resent(first))), TIMEOUT);

            assertThat(replies)
                    .as("round %d", i)
                    .allSatisfy(r -> assertThat(r.payload()).isEqualTo(new FundsReserved(payment)));
            assertThat(holdRows(payment)).isEqualTo(1);
            assertThat(ledger(payment)).containsExactly("RESERVE alice 100");
        }

        assertBalances("alice", 1_000_000 - rounds * 100L, rounds * 100L);
    }

    @Test
    @DisplayName("REQ-PF-030 concurrent reserve and release of one payment never leave money held after the release")
    void concurrentReserveAndRelease() throws Exception {
        wallet("alice", 1_000_000);
        int rounds = 50;
        for (int i = 0; i < rounds; i++) {
            UUID payment = UUID.randomUUID();
            Envelope reserveCommand = reserve(payment, "alice", 100);
            Envelope releaseCommand = release(payment, "alice", 100);

            List<Envelope> replies = concurrently(List.of(run(reserveCommand), run(releaseCommand)), TIMEOUT);

            Envelope reserveReply = replies.get(0);
            Envelope releaseReply = replies.get(1);
            assertThat(releaseReply.payload()).as("round %d", i).isEqualTo(new FundsReleased(payment));
            assertThat(holdState(payment)).as("round %d", i).isEqualTo("RELEASED");
            if (reserveReply.payload() instanceof FundsReserved) {
                assertThat(ledger(payment)).containsExactlyInAnyOrder("RESERVE alice 100", "RELEASE alice 100");
            } else {
                assertThat(reserveReply.payload()).isEqualTo(new FundsRejected(payment, "NO_ACTIVE_HOLD"));
                assertThat(ledger(payment)).isEmpty();
            }
        }

        assertThat(totalHeld()).isZero();
        assertBalances("alice", 1_000_000, 0);
    }

    @Test
    @DisplayName("REQ-PF-030 opposite-direction captures (A to B and B to A) neither deadlock nor lose money")
    void oppositeCapturesDoNotDeadlock() throws Exception {
        wallet("A", 10_000);
        wallet("B", 10_000);
        List<Envelope> captures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID ab = UUID.randomUUID();
            apply(reserve(ab, "A", 10));
            captures.add(capture(ab, "A", "B", 10));
            UUID ba = UUID.randomUUID();
            apply(reserve(ba, "B", 7));
            captures.add(capture(ba, "B", "A", 7));
        }
        long before = totalMoney();

        List<Envelope> replies = concurrently(captures.stream().map(this::run).toList(), Duration.ofSeconds(30));

        assertThat(replies).allSatisfy(r -> assertThat(r.type()).isEqualTo("FundsCaptured"));
        assertThat(totalMoney()).isEqualTo(before);
        assertBalances("A", 10_000 - 200 + 140, 0);
        assertBalances("B", 10_000 - 140 + 200, 0);
    }
}
