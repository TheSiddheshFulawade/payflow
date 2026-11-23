package com.siddheshfulawade.payflow.wallet.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.wallet.support.WalletIntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property-style test: reserve, capture and release commands for many payments, some with wrong amounts, unknown
 * wallets, duplicated (same and new message id), shuffled across payments and within a payment, with wallets
 * frozen and unfrozen in between. Whatever happens, the money invariants hold.
 */
class RandomSequencesTest extends WalletIntegrationTest {

    private static final int WALLETS = 10;
    private static final long SEED_BALANCE = 10_000;

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 42, 2024, 31337})
    @DisplayName("REQ-PF-030 random duplicated and shuffled commands keep sum(available+held) and the holds consistent")
    void invariantsSurviveAnyOrder(long seed) {
        Random random = new Random(seed);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < WALLETS; i++) {
            ids.add("w" + i);
            wallet("w" + i, SEED_BALANCE);
        }
        long seeded = WALLETS * SEED_BALANCE;

        List<Envelope> messages = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            UUID payment = UUID.randomUUID();
            String payer = random.nextInt(30) == 0 ? "ghost" : ids.get(random.nextInt(WALLETS));
            String payee = random.nextInt(30) == 0 ? "ghost" : ids.get(random.nextInt(WALLETS));
            if (payee.equals(payer)) {
                payee = ids.get((ids.indexOf(payer) + 1) % WALLETS);
            }
            long amount = 1 + random.nextInt(4_000);
            messages.add(reserve(payment, payer, amount));
            if (random.nextInt(10) < 7) {
                // 1 in 20 captures names the wrong amount
                messages.add(capture(payment, payer, payee, random.nextInt(20) == 0 ? amount + 1 : amount));
            }
            if (random.nextInt(10) < 4) {
                messages.add(release(payment, payer, amount));
            }
        }
        List<Envelope> withDuplicates = new ArrayList<>(messages);
        for (Envelope message : messages) {
            if (random.nextInt(10) < 4) {
                withDuplicates.add(resent(message));
            }
            if (random.nextInt(10) < 2) {
                withDuplicates.add(message);
            }
        }
        Collections.shuffle(withDuplicates, random);

        for (Envelope message : withDuplicates) {
            if (random.nextInt(20) == 0) {
                String[] statuses = {"ACTIVE", "FROZEN", "CLOSED"};
                jdbc.update(
                        "update wallet set status = ? where id = ?",
                        statuses[random.nextInt(statuses.length)],
                        ids.get(random.nextInt(WALLETS)));
            }
            consumer.handle(message, handler::handle);
        }

        assertThat(totalMoney()).as("sum(available + held)").isEqualTo(seeded);
        assertHeldEqualsReservedHolds();
        assertBalancesFollowTheLedger();
        assertLedgerMatchesHoldStates();
    }

    /** Σ held of a wallet == Σ amount of its RESERVED holds. */
    private void assertHeldEqualsReservedHolds() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select w.id, w.held,
                       coalesce((select sum(h.amount) from hold h where h.payer = w.id and h.state = 'RESERVED'), 0)
                           as reserved
                from wallet w""");
        assertThat(rows)
                .hasSize(WALLETS)
                .allSatisfy(row -> assertThat(((Number) row.get("held")).longValue())
                        .as("held of %s", row.get("id"))
                        .isEqualTo(((Number) row.get("reserved")).longValue()));
    }

    /** available + held == seed + credits - debits, per wallet: the ledger explains every balance. */
    private void assertBalancesFollowTheLedger() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select w.id, w.available + w.held as total,
                       coalesce((select sum(case l.kind when 'CAPTURE_CREDIT' then l.amount
                                                        when 'CAPTURE_DEBIT' then -l.amount else 0 end)
                                 from ledger_entry l where l.wallet_id = w.id), 0) as net
                from wallet w""");
        assertThat(rows)
                .allSatisfy(row -> assertThat(((Number) row.get("total")).longValue())
                        .as("total of %s", row.get("id"))
                        .isEqualTo(SEED_BALANCE + ((Number) row.get("net")).longValue()));
    }

    /** Each hold state has the ledger entries it implies, and never more than one of each. */
    private void assertLedgerMatchesHoldStates() {
        List<Map<String, Object>> holds = jdbc.queryForList("select payment_id, state from hold");
        for (Map<String, Object> hold : holds) {
            UUID payment = (UUID) hold.get("payment_id");
            String state = (String) hold.get("state");
            List<String> kinds = jdbc.queryForList(
                    "select kind from ledger_entry where payment_id = ? order by kind", String.class, payment);
            switch (state) {
                case "RESERVED" -> assertThat(kinds).as(state).containsExactly("RESERVE");
                case "CAPTURED" ->
                    assertThat(kinds).as(state).containsExactly("CAPTURE_CREDIT", "CAPTURE_DEBIT", "RESERVE");
                case "REJECTED" -> assertThat(kinds).as(state).isEmpty();
                case "RELEASED" -> assertThat(kinds).as(state).isIn(List.<String>of(), List.of("RELEASE", "RESERVE"));
                default -> throw new AssertionError("unexpected hold state " + state);
            }
        }
    }
}
