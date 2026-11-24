package com.siddheshfulawade.payflow.payment.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A validated request to pay.
 *
 * @param amount minor units
 */
public record NewPayment(String payer, String payee, long amount, String currency) {

    /**
     * SHA-256 (hex) of the fields, so JSON layout doesn't matter. Each field carries its length, so characters can't
     * move from one field to the next without changing the hash.
     *
     * <p>The hash is stored: changing this format makes a retry of an older payment look like another body (422).
     */
    public String requestHash() {
        String canonical = Stream.of(payer, payee, Long.toString(amount), currency)
                .map(field -> field.length() + ":" + field)
                .collect(Collectors.joining());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
    }
}
