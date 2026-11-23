package com.siddheshfulawade.payflow.contracts;

import java.util.regex.Pattern;

/**
 * Shared invariants of the message records. Every record is bounded at construction, so a decoded message is as
 * safe as one built in code, and every encoded message is small (a few hundred bytes, far below the codec's 64 KiB
 * limit).
 *
 * <p>Errors name the field and the rule, never the rejected value: these messages can end up in logs.
 */
final class Checks {

    /** REQ-PF-004: the largest amount of one payment, in minor units. */
    static final long MAX_AMOUNT = 10_000_000L;

    /** The only currency PayFlow moves. */
    static final String CURRENCY = "INR";

    /** Longest free-text field (reason, rule) and longest account id. */
    static final int MAX_TEXT = 64;

    /** Account ids: a small, log-safe alphabet (no spaces, control characters, quotes or markup). */
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9._@-]{1," + MAX_TEXT + "}");

    /** Money is a positive count of minor units, at most {@link #MAX_AMOUNT}; anything else is never valid. */
    static long amount(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new InvalidField("amount", "must be between 1 and " + MAX_AMOUNT);
        }
        return amount;
    }

    /** Currency is exactly {@code INR}: no other case, no padding. */
    static String currency(String currency) {
        if (!CURRENCY.equals(currency)) {
            throw new InvalidField("currency", "must be " + CURRENCY);
        }
        return currency;
    }

    /** An account id (payer, payee) matches {@code [A-Za-z0-9._@-]{1,64}}. */
    static String account(String value, String name) {
        if (value == null || !ACCOUNT.matcher(value).matches()) {
            throw new InvalidField(name, "must match " + ACCOUNT.pattern());
        }
        return value;
    }

    /** Free text (reason, rule) is present, not blank and at most {@link #MAX_TEXT} chars. */
    static String text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new InvalidField(name, "must be 1 to " + MAX_TEXT + " chars and not blank");
        }
        return value;
    }

    /**
     * A record component broke its invariant. Carries the field name so the codec can report where a message is
     * wrong without repeating what the sender wrote.
     */
    static final class InvalidField extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final String field;

        InvalidField(String field, String rule) {
            super(field + " " + rule);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    private Checks() {}
}
