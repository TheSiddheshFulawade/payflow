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
 * <p>Status correspondence: COMPLETED = CAPTURED (the amounts must be equal); REVERSED = RELEASED (nothing may be
 * captured); REJECTED = REJECTED (nothing captured), or no wallet line at all (risk rejected it before any hold). A
 * status difference wins over an amount difference: the amounts of different outcomes cannot be compared.
 *
 * <p>Invariant: a payment id is at most once in each statement. A duplicate is refused, never merged, so a double
 * capture or a double line cannot be hidden by the comparison.
 */
public final class StatementMatcher {

    private static final Set<String> PAYMENT_STATES = Set.of("COMPLETED", "REVERSED", "REJECTED");
    private static final Set<String> WALLET_STATUSES = Set.of("CAPTURED", "RELEASED", "REJECTED");

    /** Order by the text of the id: the same order as PostgreSQL "order by" on a uuid (unsigned bytes). */
    private static final Comparator<UUID> BY_TEXT = Comparator.comparing(UUID::toString);

    private StatementMatcher() {}

    /** Results ordered by payment id. */
    public static List<ReconResult> match(List<PaymentLine> payments, List<WalletLine> wallets) {
        Map<UUID, PaymentLine> paymentById = requireDistinctPayments(payments);
        Map<UUID, WalletLine> walletById = requireDistinctWallets(wallets);

        Map<UUID, Boolean> ids = new TreeMap<>(BY_TEXT);
        paymentById.keySet().forEach(id -> ids.put(id, true));
        walletById.keySet().forEach(id -> ids.put(id, true));

        List<ReconResult> results = new ArrayList<>(ids.size());
        for (UUID id : ids.keySet()) {
            results.add(new ReconResult(id, outcome(paymentById.get(id), walletById.get(id))));
        }
        return results;
    }

    /** Refuses an unknown state or a repeated payment id; the message names the id, never an amount. */
    public static Map<UUID, PaymentLine> requireDistinctPayments(List<PaymentLine> payments) {
        Map<UUID, PaymentLine> byId = new TreeMap<>(BY_TEXT);
        for (PaymentLine line : payments) {
            if (!PAYMENT_STATES.contains(line.state())) {
                throw new IllegalArgumentException("payment " + line.paymentId() + " has a non-terminal state");
            }
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
            if (!WALLET_STATUSES.contains(line.status())) {
                throw new IllegalArgumentException("payment " + line.paymentId() + " has an unknown wallet status");
            }
            if (byId.put(line.paymentId(), line) != null) {
                throw new IllegalArgumentException("payment " + line.paymentId() + " is twice in the wallet statement");
            }
        }
        return byId;
    }

    private static Outcome outcome(PaymentLine payment, WalletLine wallet) {
        if (payment == null) {
            return Outcome.MISSING_IN_PAYMENTS;
        }
        if (wallet == null) {
            return payment.state().equals("REJECTED") ? Outcome.MATCHED : Outcome.MISSING_IN_WALLET;
        }
        if (!payment.state().equals(expectedPaymentState(wallet.status()))) {
            return Outcome.STATUS_MISMATCH;
        }
        // COMPLETED moves exactly the payment amount; REVERSED and REJECTED move nothing
        long expectedCaptured = payment.state().equals("COMPLETED") ? payment.amount() : 0;
        return wallet.capturedAmount() == expectedCaptured ? Outcome.MATCHED : Outcome.AMOUNT_MISMATCH;
    }

    private static String expectedPaymentState(String walletStatus) {
        return switch (walletStatus) {
            case "CAPTURED" -> "COMPLETED";
            case "RELEASED" -> "REVERSED";
            default -> "REJECTED";
        };
    }
}
