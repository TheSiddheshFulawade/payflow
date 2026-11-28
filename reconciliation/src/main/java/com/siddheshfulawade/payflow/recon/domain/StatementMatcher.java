package com.siddheshfulawade.payflow.recon.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Compares the payment statement with the wallet statement, one result per payment id (REQ-PF-040).
 *
 * <p>Status correspondence: COMPLETED = CAPTURED (the amounts must be equal); REVERSED = RELEASED or REJECTED (nothing
 * may be captured: risk runs after the hold, so a risk reject ends REVERSED with a RELEASED hold, and a rejected hold is
 * reversed by compensation); REJECTED = REJECTED (nothing captured). A payment without a wallet line is always
 * MISSING_IN_WALLET: every reserve writes a hold row, whatever happens next. A status difference wins over an amount
 * difference: the amounts of different outcomes cannot be compared.
 *
 * <p>Midnight window: a payment and its wallet line can fall on two UTC days. For the date D the payment lines of D
 * are compared with the wallet lines of D-1, D and D+1, and a pair is reported once, on the payment line's date. A
 * wallet line of D is reported (MISSING_IN_PAYMENTS) only when no payment line of D-1, D or D+1 has its id.
 *
 * <p>Invariant: a payment id is at most once in each statement of D. A duplicate is refused, never merged, so a double
 * capture or a double line cannot be hidden by the comparison. An adjacent line is only looked up; the same id seen
 * twice with different content is refused too.
 */
public final class StatementMatcher {

    private static final Set<String> PAYMENT_STATES = Set.of("COMPLETED", "REVERSED", "REJECTED");
    private static final Set<String> WALLET_STATUSES = Set.of("CAPTURED", "RELEASED", "REJECTED");

    /** Order by the text of the id: the same order as PostgreSQL "order by" on a uuid (unsigned bytes). */
    private static final Comparator<UUID> BY_TEXT = Comparator.comparing(UUID::toString);

    private StatementMatcher() {}

    /** Results ordered by payment id, with no adjacent days. */
    public static List<ReconResult> match(List<PaymentLine> payments, List<WalletLine> wallets) {
        return match(payments, wallets, List.of(), List.of());
    }

    /**
     * Results for the business date whose lines are {@code payments} and {@code wallets}; the adjacent lists hold the
     * lines of the days before and after, used only to find the other half of a pair.
     */
    public static List<ReconResult> match(
            List<PaymentLine> payments,
            List<WalletLine> wallets,
            List<PaymentLine> adjacentPayments,
            List<WalletLine> adjacentWallets) {
        Map<UUID, PaymentLine> paymentById = requireDistinctPayments(payments);
        Map<UUID, WalletLine> walletById = requireDistinctWallets(wallets);
        Map<UUID, PaymentLine> adjacentPaymentById = lookupPayments(adjacentPayments);
        Map<UUID, WalletLine> adjacentWalletById = lookupWallets(adjacentWallets);

        Map<UUID, ReconResult> results = new TreeMap<>(BY_TEXT);
        for (PaymentLine payment : paymentById.values()) {
            WalletLine wallet =
                    walletById.getOrDefault(payment.paymentId(), adjacentWalletById.get(payment.paymentId()));
            results.put(payment.paymentId(), new ReconResult(payment.paymentId(), outcome(payment, wallet)));
        }
        for (UUID walletId : walletById.keySet()) {
            // a payment on D-1 or D+1 owns the pair: its own day reports it
            if (!paymentById.containsKey(walletId) && !adjacentPaymentById.containsKey(walletId)) {
                results.put(walletId, new ReconResult(walletId, Outcome.MISSING_IN_PAYMENTS));
            }
        }
        return new ArrayList<>(results.values());
    }

    /** Adjacent lines are validated like the others; a repeat is fine only when it is the same line. */
    private static Map<UUID, PaymentLine> lookupPayments(List<PaymentLine> lines) {
        Map<UUID, PaymentLine> byId = new TreeMap<>(BY_TEXT);
        for (PaymentLine line : lines) {
            requireKnownState(line);
            PaymentLine before = byId.putIfAbsent(line.paymentId(), line);
            if (before != null && !before.equals(line)) {
                throw new IllegalArgumentException("payment " + line.paymentId() + " differs between two days");
            }
        }
        return byId;
    }

    private static Map<UUID, WalletLine> lookupWallets(List<WalletLine> lines) {
        Map<UUID, WalletLine> byId = new TreeMap<>(BY_TEXT);
        for (WalletLine line : lines) {
            requireKnownStatus(line);
            WalletLine before = byId.putIfAbsent(line.paymentId(), line);
            if (before != null && !before.equals(line)) {
                throw new IllegalArgumentException(
                        "payment " + line.paymentId() + " has different wallet lines on two days");
            }
        }
        return byId;
    }

    private static void requireKnownState(PaymentLine line) {
        if (!PAYMENT_STATES.contains(line.state())) {
            throw new IllegalArgumentException("payment " + line.paymentId() + " has a non-terminal state");
        }
    }

    private static void requireKnownStatus(WalletLine line) {
        if (!WALLET_STATUSES.contains(line.status())) {
            throw new IllegalArgumentException("payment " + line.paymentId() + " has an unknown wallet status");
        }
    }

    /** Refuses an unknown state or a repeated payment id; the message names the id, never an amount. */
    public static Map<UUID, PaymentLine> requireDistinctPayments(List<PaymentLine> payments) {
        Map<UUID, PaymentLine> byId = new TreeMap<>(BY_TEXT);
        for (PaymentLine line : payments) {
            requireKnownState(line);
            if (byId.put(line.paymentId(), line) != null) {
                throw new IllegalArgumentException(
                        "payment " + line.paymentId() + " is twice in the payment statement");
            }
        }
        return byId;
    }

    /** Refuses an unknown status or a repeated payment id. */
    public static Map<UUID, WalletLine> requireDistinctWallets(List<WalletLine> wallets) {
        Map<UUID, WalletLine> byId = new TreeMap<>(BY_TEXT);
        for (WalletLine line : wallets) {
            requireKnownStatus(line);
            if (byId.put(line.paymentId(), line) != null) {
                throw new IllegalArgumentException("payment " + line.paymentId() + " is twice in the wallet statement");
            }
        }
        return byId;
    }

    /** Both lines exist here: a missing half is decided by the caller. */
    private static Outcome outcome(PaymentLine payment, WalletLine wallet) {
        if (wallet == null) {
            return Outcome.MISSING_IN_WALLET;
        }
        if (!statesCorrespond(payment.state(), wallet.status())) {
            return Outcome.STATUS_MISMATCH;
        }
        // COMPLETED moves exactly the payment amount; REVERSED and REJECTED move nothing
        long expectedCaptured = payment.state().equals("COMPLETED") ? payment.amount() : 0;
        return wallet.capturedAmount() == expectedCaptured ? Outcome.MATCHED : Outcome.AMOUNT_MISMATCH;
    }

    private static boolean statesCorrespond(String paymentState, String walletStatus) {
        return switch (walletStatus) {
            case "CAPTURED" -> paymentState.equals("COMPLETED");
            case "RELEASED" -> paymentState.equals("REVERSED");
            // a rejected hold: the payment is REJECTED, or REVERSED by compensation
            default -> paymentState.equals("REJECTED") || paymentState.equals("REVERSED");
        };
    }
}
