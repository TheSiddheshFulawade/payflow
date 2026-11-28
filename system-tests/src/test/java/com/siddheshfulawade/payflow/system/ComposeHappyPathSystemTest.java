package com.siddheshfulawade.payflow.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The whole stack from deploy/compose.yaml (built from the Dockerfiles), then token, seed, payment, COMPLETED
 * (PF-08, REQ-PF-051). Needs Docker and `./mvnw package` first; runs only with {@code -Psystem}. Starting and removing
 * the stack is {@link SystemStack}'s job, shared with the other system tests.
 *
 * <p>This one checks the public API only (the wallet balances); {@link EndToEndSystemTest} reads the databases.
 */
@Tag("system")
class ComposeHappyPathSystemTest {

    private static final String PAYER =
            "demo-payer-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String PAYEE =
            "demo-payee-" + UUID.randomUUID().toString().substring(0, 8);
    private static final long PAYER_BALANCE = 100_000;
    private static final long PAYEE_BALANCE = 5_000;

    @BeforeAll
    static void startStack() throws Exception {
        SystemStack.ensureUp();
    }

    @Test
    @DisplayName(
            "REQ-PF-051 compose up, token from the host, seeded wallets, one payment reaches COMPLETED and money is conserved")
    void happyPathThroughTheComposeStack() throws Exception {
        String ops = Api.token("ops");
        String alice = Api.token("alice");
        String aliceSub = Api.subject(alice);
        // the ops token may seed any wallet; owners are the users' fixed Keycloak ids
        Api.seedWallet(PAYER, aliceSub, PAYER_BALANCE);
        Api.seedWallet(PAYEE, aliceSub, PAYEE_BALANCE);

        String key = "system-" + UUID.randomUUID();
        Api.Response created = Api.post(Api.PAYMENTS, alice, key, Api.paymentBody(PAYER, PAYEE, 2_500));
        assertThat(created.status()).as("create: %s", created.body()).isEqualTo(202);
        String id = Api.JSON.readTree(created.body()).path("id").asString();
        assertThat(id).isNotBlank();

        String[] state = new String[1];
        try {
            await().atMost(Duration.ofMinutes(3))
                    .pollInterval(Duration.ofSeconds(1))
                    // a service still warming up throws: ignored, tried again until the deadline
                    .ignoreExceptions()
                    .until(() -> {
                        state[0] = Api.get(Api.PAYMENTS + "/" + id, alice)
                                .path("state")
                                .asString();
                        return List.of("COMPLETED", "REJECTED", "REVERSED").contains(state[0]);
                    });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("payment not terminal within 3 minutes, last state " + state[0], e);
        }
        assertThat(state[0]).isEqualTo("COMPLETED");

        assertInvariants(ops);
    }

    /** SPEC section 7, as far as the public API shows it: totals unchanged, nothing held, money moved once. */
    private static void assertInvariants(String ops) throws Exception {
        JsonNode payer = Api.get(Api.WALLETS + "/wallets/" + PAYER, ops);
        JsonNode payee = Api.get(Api.WALLETS + "/wallets/" + PAYEE, ops);

        long total = payer.path("available").asLong()
                + payer.path("held").asLong()
                + payee.path("available").asLong()
                + payee.path("held").asLong();
        assertThat(total).as("sum of available + held is the seeded total").isEqualTo(PAYER_BALANCE + PAYEE_BALANCE);
        assertThat(payer.path("held").asLong() + payee.path("held").asLong())
                .as("sum of held")
                .isZero();
        assertThat(payer.path("available").asLong()).as("payer debited once").isEqualTo(PAYER_BALANCE - 2_500);
        assertThat(payee.path("available").asLong()).as("payee credited once").isEqualTo(PAYEE_BALANCE + 2_500);
    }
}
