package com.siddheshfulawade.payflow.system.invariants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The money invariants checked after every system and chaos run (SPEC section 7, REQ-PF-050). It reports every broken
 * invariant, not only the first, so one failed chaos run shows the whole damage. Money is long minor units and every
 * sum uses {@link Math#addExact}, so an overflow fails loudly instead of wrapping into a false "balanced".
 *
 * <p>Reconciliation-only-MATCHED (SPEC section 7, last invariant) is NOT checked here: it belongs to PF-10.
 */
public class InvariantChecker {
    public static final String SUM_UNCHANGED = "SUM_UNCHANGED";
    public static final String NOTHING_HELD = "NOTHING_HELD";
    public static final String ALL_TERMINAL = "ALL_TERMINAL";
    public static final String ONE_CAPTURE = "ONE_CAPTURE";
    public static final String NO_MONEY_MOVED = "NO_MONEY_MOVED";
    public static final String DISTINCT_KEYS = "DISTINCT_KEYS";

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "REVERSED", "REJECTED");
    private static final Set<String> CAPTURE_KINDS = Set.of("CAPTURE_DEBIT", "CAPTURE_CREDIT");

    public List<Violation> check(Snapshot snapshot) {
        Map<UUID, List<LedgerRow>> ledgerByPayment = ledgerByPayment(snapshot.ledger());
        return Stream.of(
                        sumUnchanged(snapshot),
                        nothingHeld(snapshot.wallets()),
                        allTerminal(snapshot.payments()),
                        oneCapture(snapshot.payments(), ledgerByPayment),
                        noMoneyMoved(snapshot.payments(), ledgerByPayment),
                        distinctKeys(snapshot.payments()))
                .flatMap(List::stream)
                .toList();
    }

    // Rule: sum(available + held) over all wallets == the total seeded. Saga steps only move money between wallets
    // or between available and held, so any difference is money created or lost.
    private static List<Violation> sumUnchanged(Snapshot snapshot) {
        long total = sum(snapshot.wallets(), w -> Math.addExact(w.available(), w.held()));
        if (total == snapshot.seededTotal()) {
            return List.of();
        }
        return List.of(new Violation(
                SUM_UNCHANGED, "sum(available + held) = " + total + ", seeded = " + snapshot.seededTotal()));
    }

    // Rule: sum(held) == 0. Once every payment is terminal, every hold is captured or released.
    private static List<Violation> nothingHeld(List<WalletRow> wallets) {
        long held = sum(wallets, WalletRow::held);
        return held == 0 ? List.of() : List.of(new Violation(NOTHING_HELD, "sum(held) = " + held));
    }

    // Rule: every payment is COMPLETED, REVERSED or REJECTED (RELEASING and the in-flight states are not terminal).
    private static List<Violation> allTerminal(List<PaymentRow> payments) {
        return payments.stream()
                .filter(p -> !TERMINAL.contains(p.state()))
                .map(p -> new Violation(ALL_TERMINAL, p.id() + " is " + p.state()))
                .toList();
    }

    // Rule: a COMPLETED payment has exactly two capture entries: one CAPTURE_DEBIT on the payer and one
    // CAPTURE_CREDIT on the payee, both of the payment amount. Fewer is a lost capture, more is a double charge.
    private static List<Violation> oneCapture(List<PaymentRow> payments, Map<UUID, List<LedgerRow>> ledger) {
        List<Violation> violations = new ArrayList<>();
        for (PaymentRow p : withState(payments, Set.of("COMPLETED"))) {
            List<LedgerRow> captures = captures(ledger.getOrDefault(p.id(), List.of()));
            LedgerRow debit = new LedgerRow(p.id(), p.payer(), "CAPTURE_DEBIT", p.amount());
            LedgerRow credit = new LedgerRow(p.id(), p.payee(), "CAPTURE_CREDIT", p.amount());
            // size 2 plus both expected rows present means exactly these two (debit and credit differ by kind)
            boolean exactlyOne = captures.size() == 2 && captures.contains(debit) && captures.contains(credit);
            if (!exactlyOne) {
                violations.add(new Violation(ONE_CAPTURE, p.id() + " COMPLETED with captures " + captures));
            }
        }
        return violations;
    }

    // Rule: a REVERSED or REJECTED payment moved no money: no capture entries, and every reserve was released
    // (sum(RESERVE) - sum(RELEASE) == 0), so the payer has all of it back in available.
    private static List<Violation> noMoneyMoved(List<PaymentRow> payments, Map<UUID, List<LedgerRow>> ledger) {
        List<Violation> violations = new ArrayList<>();
        for (PaymentRow p : withState(payments, Set.of("REVERSED", "REJECTED"))) {
            List<LedgerRow> entries = ledger.getOrDefault(p.id(), List.of());
            List<LedgerRow> captures = captures(entries);
            long stillReserved = Math.subtractExact(sumOfKind(entries, "RESERVE"), sumOfKind(entries, "RELEASE"));
            if (!captures.isEmpty() || stillReserved != 0) {
                violations.add(new Violation(
                        NO_MONEY_MOVED,
                        p.id() + " " + p.state() + " with captures " + captures + ", reserve net of release "
                                + stillReserved));
            }
        }
        return violations;
    }

    // Rule: payments created == distinct (client, idempotency key). A retried request (C5) must return the original
    // payment, never create a second one; the same key from another client is a different request.
    private static List<Violation> distinctKeys(List<PaymentRow> payments) {
        Map<List<String>, Long> perKey = payments.stream()
                .collect(Collectors.groupingBy(p -> List.of(p.clientId(), p.idempotencyKey()), Collectors.counting()));
        return perKey.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(e -> new Violation(DISTINCT_KEYS, "(client, key) " + e.getKey() + " created " + e.getValue()))
                .toList();
    }

    // SEED entries have no payment id; they are not part of any payment's money movement.
    private static Map<UUID, List<LedgerRow>> ledgerByPayment(List<LedgerRow> ledger) {
        return ledger.stream().filter(e -> e.paymentId() != null).collect(Collectors.groupingBy(LedgerRow::paymentId));
    }

    private static List<PaymentRow> withState(List<PaymentRow> payments, Set<String> states) {
        return payments.stream().filter(p -> states.contains(p.state())).toList();
    }

    private static List<LedgerRow> captures(List<LedgerRow> entries) {
        return entries.stream().filter(e -> CAPTURE_KINDS.contains(e.kind())).toList();
    }

    private static long sumOfKind(List<LedgerRow> entries, String kind) {
        return sum(entries.stream().filter(e -> e.kind().equals(kind)).toList(), LedgerRow::amount);
    }

    private static <T> long sum(List<T> rows, ToLongFunction<T> amount) {
        long total = 0L;
        for (T row : rows) {
            total = Math.addExact(total, amount.applyAsLong(row));
        }
        return total;
    }
}
