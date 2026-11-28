package com.siddheshfulawade.payflow.system;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Invariant: an id that reaches a SQL text or a JSON body of the harness matches {@code [a-z0-9-]+} and nothing else,
 * so it can never break out of its quotes. Every place that builds such text goes through here.
 */
final class SafeIds {

    private SafeIds() {}

    /** The id in lower case if it is made of a-z, 0-9 and '-' only; otherwise {@link IllegalArgumentException}. */
    static String require(String id) {
        String lower = id == null ? null : id.toLowerCase(Locale.ROOT);
        if (lower == null || !lower.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("not a safe id (allowed: a-z, 0-9, '-'): " + id);
        }
        return lower;
    }

    /** {@code 'a','b'} for an SQL {@code in (...)}; every id is validated first. */
    static String sqlList(List<String> ids) {
        return ids.stream().map(id -> "'" + require(id) + "'").collect(Collectors.joining(","));
    }
}
