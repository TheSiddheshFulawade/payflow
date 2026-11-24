package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The request hash decides "same body or not" for an Idempotency-Key, so a collision would hide a real conflict. */
class NewPaymentTest {

    private static final NewPayment BASE = new NewPayment("alice", "bob", 500, "INR");

    @Test
    @DisplayName("REQ-PF-002 the request hash is 64 lowercase hex characters (SHA-256)")
    void hashIsSha256Hex() {
        assertThat(BASE.requestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("REQ-PF-002 equal fields give an equal hash")
    void hashIsDeterministic() {
        assertThat(new NewPayment("alice", "bob", 500, "INR").requestHash()).isEqualTo(BASE.requestHash());
    }

    @Test
    @DisplayName("REQ-PF-002 changing any single field changes the hash")
    void everyFieldMatters() {
        List<NewPayment> variants = List.of(
                new NewPayment("alicf", "bob", 500, "INR"),
                new NewPayment("alice", "boc", 500, "INR"),
                new NewPayment("alice", "bob", 501, "INR"),
                new NewPayment("alice", "bob", 500, "USD"));

        Set<String> hashes = new HashSet<>();
        hashes.add(BASE.requestHash());
        variants.forEach(v -> hashes.add(v.requestHash()));

        assertThat(hashes).hasSize(5);
    }

    @Test
    @DisplayName("REQ-PF-002 moving characters between payer and payee does not collide")
    void fieldBoundariesAreUnambiguous() {
        NewPayment a = new NewPayment("ab", "c", 5, "INR");
        NewPayment b = new NewPayment("a", "bc", 5, "INR");

        assertThat(a.requestHash()).isNotEqualTo(b.requestHash());
    }

    @Test
    @DisplayName("REQ-PF-002 swapping payer and payee does not collide")
    void swapDoesNotCollide() {
        assertThat(new NewPayment("bob", "alice", 500, "INR").requestHash()).isNotEqualTo(BASE.requestHash());
    }

    @Test
    @DisplayName("REQ-PF-002 moving digits between the payee and the amount does not collide")
    void amountBoundaryIsUnambiguous() {
        NewPayment a = new NewPayment("alice", "bob1", 23, "INR");
        NewPayment b = new NewPayment("alice", "bob", 123, "INR");

        assertThat(a.requestHash()).isNotEqualTo(b.requestHash());
    }
}
