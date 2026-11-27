package com.siddheshfulawade.payflow.system.invariants;

import static com.siddheshfulawade.payflow.system.invariants.InvariantChecker.ALL_TERMINAL;
import static com.siddheshfulawade.payflow.system.invariants.InvariantChecker.DISTINCT_KEYS;
import static com.siddheshfulawade.payflow.system.invariants.InvariantChecker.NOTHING_HELD;
import static com.siddheshfulawade.payflow.system.invariants.InvariantChecker.NO_MONEY_MOVED;
import static com.siddheshfulawade.payflow.system.invariants.InvariantChecker.ONE_CAPTURE;
import static com.siddheshfulawade.payflow.system.invariants.InvariantChecker.SUM_UNCHANGED;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvariantCheckerTest {

    // NOTE: "reconciliation reports only MATCHED" (SPEC section 7) is PF-10; deliberately not tested here.

    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001"); // COMPLETED 100
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002"); // REVERSED 50
    private static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-000000000003"); // REJECTED 70

    /** A run where every invariant holds: alice seeded 1000, paid bob 100; one reversed, one rejected. */
    private static final class Base {
        List<WalletRow> wallets =
                new ArrayList<>(List.of(new WalletRow("alice", 900, 0), new WalletRow("bob", 100, 0)));
        List<HoldRow> holds = new ArrayList<>(List.of(
                new HoldRow(P1, "CAPTURED", 100), new HoldRow(P2, "RELEASED", 50), new HoldRow(P3, "REJECTED", 70)));
        List<LedgerRow> ledger = new ArrayList<>(List.of(
                new LedgerRow(null, "alice", "SEED", 1000),
                new LedgerRow(P1, "alice", "RESERVE", 100),
                new LedgerRow(P1, "alice", "CAPTURE_DEBIT", 100),
                new LedgerRow(P1, "bob", "CAPTURE_CREDIT", 100),
                new LedgerRow(P2, "alice", "RESERVE", 50),
                new LedgerRow(P2, "alice", "RELEASE", 50)));
        List<PaymentRow> payments = new ArrayList<>(List.of(
                new PaymentRow(P1, "c1", "k1", "alice", "bob", 100, "COMPLETED"),
                new PaymentRow(P2, "c1", "k2", "alice", "bob", 50, "REVERSED"),
                new PaymentRow(P3, "c1", "k3", "alice", "bob", 70, "REJECTED")));
        long seeded = 1000;

        Snapshot build() {
            return new Snapshot(wallets, holds, ledger, payments, seeded);
        }
    }

    private final InvariantChecker checker = new InvariantChecker();

    private static List<String> names(List<Violation> violations) {
        return violations.stream().map(Violation::invariant).toList();
    }

    @Test
    @DisplayName("REQ-PF-050 a clean run has no violations")
    void cleanRun() {
        assertThat(checker.check(new Base().build())).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-050 money created or lost (sum of available+held differs from seeded) is reported")
    void sumChanged() {
        Base b = new Base();
        b.wallets.set(1, new WalletRow("bob", 101, 0));
        assertThat(names(checker.check(b.build()))).containsExactly(SUM_UNCHANGED);
    }

    @Test
    @DisplayName("REQ-PF-050 money still held after the run is reported")
    void heldNotZero() {
        Base b = new Base();
        // the sum stays 1000, so only the held invariant breaks
        b.wallets.set(0, new WalletRow("alice", 890, 10));
        assertThat(names(checker.check(b.build()))).containsExactly(NOTHING_HELD);
    }

    @Test
    @DisplayName("REQ-PF-050 a payment stuck in a non-terminal state is reported")
    void notTerminal() {
        Base b = new Base();
        b.payments.set(2, new PaymentRow(P3, "c1", "k3", "alice", "bob", 70, "CAPTURING"));
        assertThat(names(checker.check(b.build()))).contains(ALL_TERMINAL);
    }

    @Test
    @DisplayName("REQ-PF-050 RELEASING is not terminal")
    void releasingNotTerminal() {
        Base b = new Base();
        b.payments.set(1, new PaymentRow(P2, "c1", "k2", "alice", "bob", 50, "RELEASING"));
        assertThat(names(checker.check(b.build()))).contains(ALL_TERMINAL);
    }

    @Test
    @DisplayName("REQ-PF-050 COMPLETED without its payee credit is reported")
    void completedMissingCredit() {
        Base b = new Base();
        b.ledger.remove(new LedgerRow(P1, "bob", "CAPTURE_CREDIT", 100));
        assertThat(names(checker.check(b.build()))).contains(ONE_CAPTURE);
    }

    @Test
    @DisplayName("REQ-PF-050 COMPLETED captured twice is reported")
    void completedCapturedTwice() {
        Base b = new Base();
        b.ledger.add(new LedgerRow(P1, "alice", "CAPTURE_DEBIT", 100));
        assertThat(names(checker.check(b.build()))).contains(ONE_CAPTURE);
    }

    @Test
    @DisplayName("REQ-PF-050 COMPLETED whose capture amount differs from the payment amount is reported")
    void completedWrongAmount() {
        Base b = new Base();
        b.ledger.set(2, new LedgerRow(P1, "alice", "CAPTURE_DEBIT", 99));
        assertThat(names(checker.check(b.build()))).contains(ONE_CAPTURE);
    }

    @Test
    @DisplayName("REQ-PF-050 COMPLETED whose debit hits a wallet other than the payer is reported")
    void completedWrongWallet() {
        Base b = new Base();
        b.ledger.set(2, new LedgerRow(P1, "carol", "CAPTURE_DEBIT", 100));
        assertThat(names(checker.check(b.build()))).contains(ONE_CAPTURE);
    }

    @Test
    @DisplayName("REQ-PF-050 REVERSED that also captured money is reported")
    void reversedButCaptured() {
        Base b = new Base();
        b.ledger.add(new LedgerRow(P2, "alice", "CAPTURE_DEBIT", 50));
        assertThat(names(checker.check(b.build()))).contains(NO_MONEY_MOVED);
    }

    @Test
    @DisplayName("REQ-PF-050 REVERSED whose reserve was never released is reported")
    void reversedNotReleased() {
        Base b = new Base();
        b.ledger.remove(new LedgerRow(P2, "alice", "RELEASE", 50));
        assertThat(names(checker.check(b.build()))).contains(NO_MONEY_MOVED);
    }

    @Test
    @DisplayName("REQ-PF-050 REJECTED with any ledger entry is reported")
    void rejectedMovedMoney() {
        Base b = new Base();
        b.ledger.add(new LedgerRow(P3, "alice", "RESERVE", 70));
        assertThat(names(checker.check(b.build()))).contains(NO_MONEY_MOVED);
    }

    @Test
    @DisplayName("REQ-PF-050 two payments with the same client and idempotency key are reported")
    void duplicateKey() {
        Base b = new Base();
        b.payments.set(2, new PaymentRow(P3, "c1", "k1", "alice", "bob", 70, "REJECTED"));
        assertThat(names(checker.check(b.build()))).containsExactly(DISTINCT_KEYS);
    }

    @Test
    @DisplayName("REQ-PF-050 the same key from different clients is not a duplicate")
    void sameKeyOtherClient() {
        Base b = new Base();
        b.payments.set(2, new PaymentRow(P3, "c2", "k1", "alice", "bob", 70, "REJECTED"));
        assertThat(checker.check(b.build())).isEmpty();
    }

    @Test
    @DisplayName("REQ-PF-050 all broken invariants are reported at once, not only the first")
    void reportsAll() {
        Base b = new Base();
        b.wallets.set(1, new WalletRow("bob", 105, 5)); // sum +10 and held 5
        // not terminal and a duplicate key
        b.payments.set(2, new PaymentRow(P3, "c1", "k1", "alice", "bob", 70, "RESERVING"));
        assertThat(names(checker.check(b.build()))).contains(SUM_UNCHANGED, NOTHING_HELD, ALL_TERMINAL, DISTINCT_KEYS);
    }

    @Test
    @DisplayName("REQ-PF-050 money is long: sums above Integer.MAX_VALUE do not overflow")
    void moneyIsLong() {
        long big = 3_000_000_000L;
        List<WalletRow> wallets = List.of(new WalletRow("a", big, 0), new WalletRow("b", big, 0));
        Snapshot ok = new Snapshot(wallets, List.of(), List.of(), List.of(), 2 * big);
        assertThat(checker.check(ok)).isEmpty();
        Snapshot bad = new Snapshot(wallets, List.of(), List.of(), List.of(), 2 * big + 1);
        assertThat(names(checker.check(bad))).containsExactly(SUM_UNCHANGED);
        for (Class<?> c : List.of(WalletRow.class, HoldRow.class, LedgerRow.class, PaymentRow.class, Snapshot.class)) {
            for (RecordComponent rc : c.getRecordComponents()) {
                if (rc.getName().matches("available|held|amount|seededTotal")) {
                    assertThat(rc.getType())
                            .as(c.getSimpleName() + "." + rc.getName())
                            .isEqualTo(long.class);
                }
            }
        }
    }
}
