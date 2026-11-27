package com.siddheshfulawade.payflow.payment.api;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** The mapping from a token's {@code realm_access.roles} to authorities (see {@link RealmRoles}). */
class RealmRolesTest {

    // TODO-ME: write this test, then remove @Disabled. Build a Jwt with
    // Jwt.withTokenValue("t").header("alg", "none")
    // .claim("realm_access", Map.of("roles", List.of(42, "payments:read"))).build(), call
    // new RealmRoles().convert(jwt), and assert that the result holds exactly one authority,
    // "payments:read": the number 42 is dropped, and nothing throws. Check: run
    // `mvnw.cmd -q -pl payment-service test -Dtest=RealmRolesTest`; it must pass. Then temporarily delete the
    // `.filter(String.class::isInstance)` line in RealmRoles and confirm your test fails (a ClassCastException).
    @Disabled("TODO-ME")
    @Test
    void REQ_PF_003_a_role_that_is_not_a_string_grants_nothing() {}
}
