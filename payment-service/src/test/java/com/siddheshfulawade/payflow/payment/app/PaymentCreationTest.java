package com.siddheshfulawade.payflow.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import com.siddheshfulawade.payflow.payment.support.PaymentIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Creating a payment: one transaction holds the row, its audit events and the first command. */
class PaymentCreationTest extends PaymentIntegrationTest {

    @Test
    @DisplayName("REQ-PF-001 a new payment is stored with its owner, hash and the first step on attempt 1")
    void storesThePaymentRow() {
        NewPayment request = body();

        Payment created = newPayment(request);

        assertThat(created.state()).isEqualTo(PaymentState.RESERVING);
        assertThat(created.stepAttempts()).isEqualTo(1);
        assertThat(state(created.id())).isEqualTo("RESERVING");
        assertThat(stepAttempts(created.id())).isEqualTo(1);
        assertThat(version(created.id()))
                .as("RECEIVED insert, then one transition")
                .isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "select client_id, payer, payee, amount, currency, request_hash from payment where id = ?",
                        created.id()))
                .containsEntry("client_id", "local")
                .containsEntry("payer", "alice")
                .containsEntry("payee", "bob")
                .containsEntry("amount", 500L)
                .containsEntry("currency", "INR")
                .containsEntry("request_hash", request.requestHash());
    }

    @Test
    @DisplayName("REQ-PF-001 the audit trail shows RECEIVED and then RECEIVED to RESERVING")
    void writesTheFirstTwoEvents() {
        Payment created = newPayment();

        assertThat(events(created.id())).containsExactlyInAnyOrder("null->RECEIVED", "RECEIVED->RESERVING");
    }

    @Test
    @DisplayName("REQ-PF-020 the ReserveFunds command is in the outbox, built from the payment, attempt 1")
    void queuesReserveFunds() {
        Payment created = newPayment(new NewPayment("carol", "dave", 1_234, "INR"));

        assertThat(outboxCount(created.id())).isEqualTo(1);
        Envelope command = onlyOutbox(created.id(), ReserveFunds.class);
        assertThat(command.payload()).isEqualTo(new ReserveFunds(created.id(), "carol", 1_234, "INR", "local"));
        assertThat(command.attempt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select topic from outbox where message_key = ?",
                        String.class,
                        created.id().toString()))
                .isEqualTo("wallet.commands");
    }

    @Test
    @DisplayName("REQ-PF-012 the reserve step gets a deadline 10 seconds ahead")
    void setsTheReserveDeadline() {
        Instant before = Instant.now();

        Payment created = newPayment();

        assertDeadline(created.id(), Duration.ofSeconds(10), before, Instant.now());
    }

    @Test
    @DisplayName("REQ-PF-002 the same key and body again returns the original and changes nothing")
    void repeatReturnsTheOriginal() {
        String key = freshKey();
        PaymentService.Created first = payments.create("local", key, body());
        Snapshot afterFirst = snapshotAll();

        PaymentService.Created second = payments.create("local", key, body());

        assertThat(first.isNew()).isTrue();
        assertThat(second.isNew()).isFalse();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(snapshotAll()).as("no row, event or message added").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("REQ-PF-002 a repeat after progress returns the current state, not the state at creation")
    void repeatShowsTheCurrentState() {
        String key = freshKey();
        UUID id = payments.create("local", key, body()).payment().id();
        deliver(reply(new FundsReserved(id)));

        PaymentService.Created again = payments.create("local", key, body());

        assertThat(again.isNew()).isFalse();
        assertThat(again.payment().id()).isEqualTo(id);
        assertThat(again.payment().state()).isEqualTo(PaymentState.RISK_CHECKING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payer", "payee", "amount"})
    @DisplayName("REQ-PF-002 the same key with a different body is refused and changes nothing")
    void differentBodyIsRefused(String field) {
        String key = freshKey();
        payments.create("local", key, body());
        Snapshot before = snapshotAll();
        NewPayment other = switch (field) {
            case "payer" -> new NewPayment("alicf", "bob", 500, "INR");
            case "payee" -> new NewPayment("alice", "boc", 500, "INR");
            default -> new NewPayment("alice", "bob", 501, "INR");
        };

        assertThatThrownBy(() -> payments.create("local", key, other))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(snapshotAll()).isEqualTo(before);
    }

    @Test
    @DisplayName("REQ-PF-002 two keys with the same body are two payments")
    void sameBodyDifferentKeys() {
        Payment a = newPayment();
        Payment b = newPayment();

        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(paymentRows()).isEqualTo(2);
        assertThat(outboxCount(a.id())).isEqualTo(1);
        assertThat(outboxCount(b.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-002 the same key from another client is a separate payment")
    void keysAreScopedPerClient() {
        String key = freshKey();

        PaymentService.Created mine = payments.create("local", key, body());
        PaymentService.Created theirs = payments.create("other-client", key, body());

        assertThat(mine.isNew()).isTrue();
        assertThat(theirs.isNew()).isTrue();
        assertThat(theirs.payment().id()).isNotEqualTo(mine.payment().id());
        assertThat(paymentRows()).isEqualTo(2);
        assertThat(outboxCount(mine.payment().id())).isEqualTo(1);
        assertThat(outboxCount(theirs.payment().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-002 20 concurrent creates with one key make one payment, one command and one id for all")
    void concurrentCreatesWithOneKey() throws Exception {
        String key = freshKey();
        List<Callable<PaymentService.Created>> tasks = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> tasks.add(() -> payments.create("local", key, body())));

        List<Object> results = concurrently(tasks, TIMEOUT);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(PaymentService.Created.class));
        List<PaymentService.Created> created =
                results.stream().map(r -> (PaymentService.Created) r).toList();
        assertThat(created.stream().map(c -> c.payment().id()).distinct()).hasSize(1);
        assertThat(created.stream().filter(PaymentService.Created::isNew)).hasSize(1);
        assertThat(paymentRows()).isEqualTo(1);
        UUID id = created.get(0).payment().id();
        assertThat(outboxCount(id)).as("one ReserveFunds").isEqualTo(1);
        assertThat(events(id)).containsExactlyInAnyOrder("null->RECEIVED", "RECEIVED->RESERVING");
    }

    @Test
    @DisplayName("REQ-PF-002 concurrent creates with one key and two bodies: one wins, the others are 422 material")
    void concurrentCreatesWithTwoBodies() throws Exception {
        String key = freshKey();
        NewPayment a = new NewPayment("alice", "bob", 500, "INR");
        NewPayment b = new NewPayment("alice", "bob", 600, "INR");
        List<Callable<PaymentService.Created>> tasks = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> {
            NewPayment mine = i % 2 == 0 ? a : b;
            tasks.add(() -> payments.create("local", key, mine));
        });

        List<Object> results = concurrently(tasks, TIMEOUT);

        assertThat(results)
                .allSatisfy(r -> assertThat(r)
                        .satisfiesAnyOf(
                                x -> assertThat(x).isInstanceOf(PaymentService.Created.class),
                                x -> assertThat(x).isInstanceOf(IdempotencyKeyReusedException.class)));
        assertThat(paymentRows()).isEqualTo(1);
        UUID id = jdbc.queryForObject("select id from payment", UUID.class);
        assertThat(outboxCount(id)).isEqualTo(1);
        String storedHash = jdbc.queryForObject("select request_hash from payment", String.class);
        long expectedSuccesses = results.stream()
                .filter(PaymentService.Created.class::isInstance)
                .count();
        long winnersBody = IntStream.range(0, 20)
                .filter(i -> (i % 2 == 0 ? a : b).requestHash().equals(storedHash))
                .count();
        assertThat(expectedSuccesses)
                .as("only callers with the winning body succeed")
                .isEqualTo(winnersBody);
    }

    @Test
    @DisplayName("REQ-PF-020 when the outbox append fails, the payment and its events are not stored either")
    void failedAppendRollsBackTheCreate() {
        String key = freshKey();
        makeOutboxInsertFail("wallet.commands");

        assertThatThrownBy(() -> payments.create("local", key, body())).isInstanceOf(RuntimeException.class);

        assertThat(paymentRows()).isZero();
        assertThat(count("select count(*) from payment_event")).isZero();
        assertThat(count("select count(*) from outbox")).isZero();
    }

    @Test
    @DisplayName("REQ-PF-002 a create that failed does not burn its Idempotency-Key: the retry makes the payment")
    void failedCreateCanBeRetriedWithTheSameKey() {
        String key = freshKey();
        makeOutboxInsertFail("wallet.commands");
        assertThatThrownBy(() -> payments.create("local", key, body())).isInstanceOf(RuntimeException.class);
        dropFailureTriggers();

        PaymentService.Created retry = payments.create("local", key, body());

        assertThat(retry.isNew()).isTrue();
        assertThat(outboxCount(retry.payment().id())).isEqualTo(1);
        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-PF-010 find returns the stored payment, and empty for an unknown id")
    void findReadsTheRow() {
        Payment created = newPayment();

        assertThat(payments.find(created.id())).hasValueSatisfying(p -> {
            assertThat(p.id()).isEqualTo(created.id());
            assertThat(p.state()).isEqualTo(PaymentState.RESERVING);
            assertThat(p.payer()).isEqualTo("alice");
        });
        assertThat(payments.find(UUID.randomUUID())).isEmpty();
    }
}
