package com.siddheshfulawade.payflow.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

/** The database is the last line of defence: it refuses rows the code must never produce. */
class PaymentSchemaTest extends PaymentIntegrationTest {

    private static final String ACTIVE_DEADLINE = "now() + interval '10 seconds'";

    /** A valid RESERVING payment unless a parameter says otherwise. */
    private void insert(
            String clientId,
            String key,
            String payer,
            String payee,
            long amount,
            String currency,
            String state,
            int stepAttempts,
            String deadlineSql,
            long version) {
        jdbc.update(
                "insert into payment(id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,"
                        + " state, step_attempts, deadline_at, version, created_at, updated_at)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + deadlineSql + ", ?, now(), now())",
                UUID.randomUUID(),
                clientId,
                key,
                "0".repeat(64),
                payer,
                payee,
                amount,
                currency,
                state,
                stepAttempts,
                version);
    }

    private void insertValid() {
        insert("local", freshKey(), "alice", "bob", 500, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);
    }

    private void assertRefused(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentRows()).as("nothing stored").isZero();
    }

    @Test
    @DisplayName("REQ-PF-001 the baseline row of these tests is valid (so each refusal below has one cause)")
    void baselineIsValid() {
        insertValid();

        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-004 amounts 1 and 10,000,000 are stored; 0, -1 and 10,000,001 are refused")
    void amountBounds() {
        insert("local", freshKey(), "alice", "bob", 1, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);
        insert("local", freshKey(), "alice", "bob", 10_000_000, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);
        assertThat(paymentRows()).isEqualTo(2);

        for (long bad : new long[] {0, -1, 10_000_001}) {
            assertThatThrownBy(() ->
                            insert("local", freshKey(), "alice", "bob", bad, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0))
                    .as("amount %d", bad)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(paymentRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-PF-004 the database refuses a currency other than INR")
    void currencyCheck() {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "USD", "RESERVING", 1, ACTIVE_DEADLINE, 0));
    }

    @Test
    @DisplayName("REQ-PF-004 the database refuses a payment to oneself")
    void payerAndPayeeDiffer() {
        assertRefused(
                () -> insert("local", freshKey(), "alice", "alice", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0));
    }

    @Test
    @DisplayName("REQ-PF-010 the database refuses an unknown state")
    void stateCheck() {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", "BOGUS", 1, ACTIVE_DEADLINE, 0));
    }

    @Test
    @DisplayName("REQ-PF-010 the database refuses negative step attempts and a negative version")
    void counterChecks() {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", "RESERVING", -1, ACTIVE_DEADLINE, 0));
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, -1));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RESERVING", "RISK_CHECKING", "CAPTURING", "RELEASING"})
    @DisplayName("REQ-PF-012 an in-flight payment must have a deadline, or the sweeper would never see it")
    void inFlightNeedsADeadline(PaymentState state) {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 1, "null", 0));
        insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 1, ACTIVE_DEADLINE, 0);

        assertThat(paymentRows()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"RECEIVED", "COMPLETED", "REVERSED", "REJECTED"})
    @DisplayName("REQ-PF-012 RECEIVED and terminal payments must have no deadline")
    void idleAndTerminalHaveNoDeadline(PaymentState state) {
        assertRefused(() -> insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 0, ACTIVE_DEADLINE, 0));
        insert("local", freshKey(), "alice", "bob", 5, "INR", state.name(), 0, "null", 0);

        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-002 one client cannot have the same Idempotency-Key twice, two clients can")
    void keyIsUniquePerClient() {
        String key = freshKey();
        insert("local", key, "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);

        assertThatThrownBy(() -> insert("local", key, "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert("other", key, "alice", "bob", 5, "INR", "RESERVING", 1, ACTIVE_DEADLINE, 0);

        assertThat(paymentRows()).isEqualTo(2);
    }

    // ---- text columns: the alphabets the API enforces are enforced by the database too ----

    private static final String HASH = "0".repeat(64);

    /** A valid RESERVING payment with the given text columns; everything else as in {@link #insertValid()}. */
    private void insertText(String key, String hash, String payer, String payee) {
        jdbc.update(
                "insert into payment(id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,"
                        + " state, step_attempts, deadline_at, version, created_at, updated_at)"
                        + " values (?, 'local', ?, ?, ?, ?, 5, 'INR', 'RESERVING', 1, " + ACTIVE_DEADLINE
                        + ", 0, now(), now())",
                UUID.randomUUID(),
                key,
                hash,
                payer,
                payee);
    }

    private void assertAllRefused(String column, List<String> bad, Consumer<String> insert) {
        for (String value : bad) {
            assertThatThrownBy(() -> insert.accept(value))
                    .as("%s = %s", column, printable(value))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(paymentRows()).as("nothing stored").isZero();
    }

    private static String printable(String value) {
        return value.length() > 20 ? value.length() + " chars" : value.replace("\n", "\\n");
    }

    private static final List<String> NOT_IDENTIFIERS = List.of(
            "", ".alice", "-alice", "_alice", "@alice", "al ice", "alice\n", "ali/ce", "aliéce", "a".repeat(65));

    @Test
    @DisplayName(
            "REQ-PF-004 payer: 1 to 64 of letters, digits . _ @ - and starting alphanumeric; anything else is refused")
    void payerAlphabet() {
        for (String ok : List.of("a", "7", "Alice.B_c@d-e", "a".repeat(64))) {
            insertText(freshKey(), HASH, ok, "bob");
        }
        assertThat(paymentRows()).isEqualTo(4);
        jdbc.execute("truncate table payment cascade");

        assertAllRefused("payer", NOT_IDENTIFIERS, bad -> insertText(freshKey(), HASH, bad, "bob"));
    }

    @Test
    @DisplayName("REQ-PF-004 payee: the same alphabet and length as payer")
    void payeeAlphabet() {
        for (String ok : List.of("a", "7", "Bob.B_c@d-e", "b".repeat(64))) {
            insertText(freshKey(), HASH, "alice", ok);
        }
        assertThat(paymentRows()).isEqualTo(4);
        jdbc.execute("truncate table payment cascade");

        assertAllRefused("payee", NOT_IDENTIFIERS, bad -> insertText(freshKey(), HASH, "alice", bad));
    }

    @Test
    @DisplayName("REQ-PF-002 request_hash is exactly 64 lowercase hex characters")
    void requestHashShape() {
        for (String ok : List.of("0".repeat(64), "0123456789abcdef".repeat(4))) {
            insertText(freshKey(), ok, "alice", "bob");
        }
        assertThat(paymentRows()).isEqualTo(2);
        jdbc.execute("truncate table payment cascade");

        List<String> bad = List.of(
                "",
                "0".repeat(63),
                "0".repeat(65),
                "A".repeat(64),
                "g".repeat(64),
                "0".repeat(63) + "\n",
                "0".repeat(32) + " " + "0".repeat(31));
        assertAllRefused("request_hash", bad, hash -> insertText(freshKey(), hash, "alice", "bob"));
    }

    @Test
    @DisplayName("REQ-PF-002 idempotency_key is 1 to 64 of letters, digits . _ - (the same as the API accepts)")
    void idempotencyKeyAlphabet() {
        for (String ok : List.of("a", "A.b_c-1", "k".repeat(64))) {
            insertText(ok, HASH, "alice", "bob");
        }
        assertThat(paymentRows()).isEqualTo(3);
        jdbc.execute("truncate table payment cascade");

        List<String> bad = List.of("", "k".repeat(65), "a b", "key\n", "a/b", "a@b", "kéy", "a:b");
        assertAllRefused("idempotency_key", bad, key -> insertText(key, HASH, "alice", "bob"));
    }

    // ---- payment_event: known states, each state once, one terminal state ----

    private UUID storedPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into payment(id, client_id, idempotency_key, request_hash, payer, payee, amount, currency,"
                        + " state, step_attempts, deadline_at, version, created_at, updated_at)"
                        + " values (?, 'local', ?, ?, 'alice', 'bob', 5, 'INR', 'RESERVING', 1, " + ACTIVE_DEADLINE
                        + ", 0, now(), now())",
                id,
                freshKey(),
                HASH);
        return id;
    }

    private void addEvent(UUID payment, String from, String to) {
        jdbc.update(
                "insert into payment_event(payment_id, from_state, to_state, created_at) values (?, ?, ?, now())",
                payment,
                from,
                to);
    }

    @ParameterizedTest
    @EnumSource(PaymentState.class)
    @DisplayName("REQ-PF-010 every known state is accepted as to_state, and as from_state (which may also be null)")
    void eventAcceptsKnownStates(PaymentState state) {
        UUID asTarget = storedPayment();
        UUID asSource = storedPayment();

        addEvent(asTarget, null, state.name());
        addEvent(asSource, state.name(), "CAPTURING");

        assertThat(eventCount(asTarget)).isEqualTo(1);
        assertThat(eventCount(asSource)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-010 the database refuses an event with an unknown to_state")
    void eventToStateCheck() {
        UUID id = storedPayment();

        for (String bad : List.of("BOGUS", "reserving", "", "COMPLETED ")) {
            assertThatThrownBy(() -> addEvent(id, "RESERVING", bad))
                    .as("to_state = '%s'", bad)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(eventCount(id)).isZero();
    }

    @Test
    @DisplayName("REQ-PF-010 the database refuses an event with an unknown from_state, but allows none (null)")
    void eventFromStateCheck() {
        UUID id = storedPayment();

        for (String bad : List.of("BOGUS", "reserving", "")) {
            assertThatThrownBy(() -> addEvent(id, bad, "RISK_CHECKING"))
                    .as("from_state = '%s'", bad)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(eventCount(id)).isZero();
        addEvent(id, null, "RISK_CHECKING");
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-010 a payment enters a state at most once, whatever the from_state; another payment may")
    void eachStateOncePerPayment() {
        UUID id = storedPayment();
        UUID other = storedPayment();
        addEvent(id, null, "RECEIVED");
        addEvent(id, "RECEIVED", "RESERVING");

        assertThatThrownBy(() -> addEvent(id, "RECEIVED", "RESERVING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> addEvent(id, null, "RESERVING")).isInstanceOf(DataIntegrityViolationException.class);

        addEvent(other, "RECEIVED", "RESERVING");
        assertThat(eventCount(id)).isEqualTo(2);
        assertThat(eventCount(other)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentState.class,
            names = {"COMPLETED", "REVERSED", "REJECTED"})
    @DisplayName("REQ-PF-010 a payment has at most one terminal event: a second, different terminal state is refused")
    void secondTerminalIsRefused(PaymentState first) {
        UUID id = storedPayment();
        UUID other = storedPayment();
        addEvent(id, "CAPTURING", first.name());

        for (String second : List.of("COMPLETED", "REVERSED", "REJECTED")) {
            assertThatThrownBy(() -> addEvent(id, "RELEASING", second))
                    .as("%s after %s", second, first)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        addEvent(other, "CAPTURING", first.name());
        assertThat(eventCount(id)).isEqualTo(1);
        assertThat(eventCount(other)).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-004 a refused row's values are not in the exception (logServerErrorDetail=false)")
    void errorsDoNotEchoRowValues() {
        assertThatThrownBy(() -> insert(
                        "local",
                        freshKey(),
                        "leaky-payer-77",
                        "leaky-payee-77",
                        0,
                        "INR",
                        "RESERVING",
                        1,
                        ACTIVE_DEADLINE,
                        0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .message()
                .doesNotContain("leaky-payer-77")
                .doesNotContain("leaky-payee-77");
    }
}
