package com.siddheshfulawade.payflow.contracts;

import java.util.regex.Pattern;

/**
 * The one rule for a token subject (the JWT {@code sub}) that travels in a command or is stored as a wallet owner.
 *
 * <p>Invariant: a subject is 1 to 64 printable ASCII characters ({@code [\x21-\x7E]}). Keycloak subjects are 36-char
 * UUIDs; printable ASCII keeps control, line-break, bidirectional and zero-width characters (and spaces) out of logs
 * and messages. Every layer uses this class, so the rule cannot drift between services.
 */
public final class Subjects {

    /** Longest subject, in characters. */
    public static final int MAX_LENGTH = 64;

    /** The rule as a regular expression, for Jakarta Validation's {@code @Pattern}. */
    public static final String PATTERN = "[\\x21-\\x7E]{1," + MAX_LENGTH + "}";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    /** True when {@code value} is a usable subject; false for null. */
    public static boolean isValid(String value) {
        return value != null && COMPILED.matcher(value).matches();
    }

    private Subjects() {}
}
