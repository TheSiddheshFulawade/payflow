package com.siddheshfulawade.payflow.payment.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.FundsCaptured;
import com.siddheshfulawade.payflow.contracts.FundsRejected;
import com.siddheshfulawade.payflow.contracts.FundsReleased;
import com.siddheshfulawade.payflow.contracts.FundsReserved;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Reply;
import com.siddheshfulawade.payflow.contracts.RiskApproved;
import com.siddheshfulawade.payflow.contracts.RiskRejected;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.PaymentServiceApplication;
import com.siddheshfulawade.payflow.payment.app.NewPayment;
import com.siddheshfulawade.payflow.payment.app.PaymentService;
import com.siddheshfulawade.payflow.payment.app.PaymentSweeper;
import com.siddheshfulawade.payflow.payment.app.ReplyHandler;
import com.siddheshfulawade.payflow.payment.domain.Payment;
import com.siddheshfulawade.payflow.payment.domain.PaymentState;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared context for the payment integration tests: real PostgreSQL and Kafka, the relay switched off (the outbox
 * rows stay readable). Replies reach the handler the way production does it:
 * {@code IdempotentConsumer.handle(envelope, replyHandler::handle)}.
 *
 * <p>The sweeper's schedule is off (a test calls {@code sweeper.sweep()} itself) and the clock is a {@link MutableClock}
 * (system time until a test sets it), so a deadline passes by moving the clock, never by waiting. A subclass that wants
 * the schedule overrides {@code payflow.payment.sweeper.enabled} with its own @TestPropertySource.
 */
@Tag("it")
@SpringBootTest(classes = {PaymentServiceApplication.class, PaymentTestBeans.class})
@TestPropertySource(properties = "payflow.payment.sweeper.enabled=false")
@AutoConfigureMockMvc
public abstract class PaymentIntegrationTest {

    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    // TRUNCATE locks tables in the listed order; list them in the order the application's transactions lock them
    // (inbox, payment, event, outbox), so it can only wait for a running sweep or reply, never deadlock with it.
    public static final String CLEANUP = "truncate table inbox, payment, payment_event, outbox";

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.flyway.locations", () -> "classpath:db/messaging,classpath:db/migration");
        registry.add("payflow.messaging.relay.enabled", () -> "false");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected IdempotentConsumer consumer;

    @Autowired
    protected ReplyHandler handler;

    @Autowired
    protected PaymentService payments;

    @Autowired
    protected PaymentSweeper sweeper;

    @Autowired
    protected MutableClock clock;

    protected final MessageCodec codec = new MessageCodec();
    protected final KafkaProbe kafka = new KafkaProbe();

    @BeforeEach
    void cleanState() {
        dropFailureTriggers();
        clock.useSystemTime();
        jdbc.execute(CLEANUP);
    }

    /** A test that failed halfway must not leave a trigger behind for the next one. */
    @AfterEach
    void removeTriggers() {
        dropFailureTriggers();
    }

    // ---- fixtures ----

    protected static String freshKey() {
        return "key-" + UUID.randomUUID();
    }

    protected static NewPayment body() {
        return new NewPayment("alice", "bob", 500, "INR");
    }

    /** A new payment of client "local" under a fresh key; it is already RESERVING with ReserveFunds in the outbox. */
    protected Payment newPayment() {
        return newPayment(body());
    }

    protected Payment newPayment(NewPayment body) {
        PaymentService.Created created = payments.create("local", freshKey(), body);
        assertThat(created.isNew()).as("fresh key creates a payment").isTrue();
        return created.payment();
    }

    // ---- replies (a fresh message id each time) ----

    protected static Envelope reply(Reply payload) {
        return reply(payload, 1);
    }

    protected static Envelope reply(Reply payload, int attempt) {
        return Envelope.of(payload, attempt, Clock.systemUTC());
    }

    /** The same reply content sent again by the wallet: a NEW message id, so the inbox does not catch it. */
    protected static Envelope resent(Envelope original) {
        return new Envelope(
                UUID.randomUUID(),
                original.paymentId(),
                original.type(),
                original.version(),
                original.attempt(),
                original.occurredAt(),
                original.payload());
    }

    /** Runs the reply through the idempotent consumer, as the Kafka listener does. */
    protected IdempotentConsumer.Outcome deliver(Envelope reply) {
        return consumer.handle(reply, handler::handle);
    }

    /**
     * A poison message is thrown out of the consumer and leaves no trace: no inbox row, nothing in the outbox, no
     * event, and the exception text does not contain the given value.
     */
    protected void assertPoison(Envelope message, String mustNotAppear) {
        Snapshot before = snapshotAll();

        assertThatThrownBy(() -> deliver(message))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain(mustNotAppear);

        assertThat(snapshotAll()).as("nothing changed").isEqualTo(before);
        assertThat(count("select count(*) from inbox where message_id = ?", message.messageId()))
                .as("inbox row rolled back")
                .isZero();
    }

    /** Drives a RESERVING payment forward with genuine replies until it is in {@code target}. */
    protected void advanceTo(UUID id, PaymentState target) {
        List<Reply> replies = switch (target) {
            case RESERVING -> List.of();
            case RISK_CHECKING -> List.of(new FundsReserved(id));
            case CAPTURING -> List.of(new FundsReserved(id), new RiskApproved(id));
            case COMPLETED -> List.of(new FundsReserved(id), new RiskApproved(id), new FundsCaptured(id));
            case REJECTED -> List.of(new FundsRejected(id, "INSUFFICIENT_FUNDS"));
            // compensation after a risk rejection (the capture-failure path is driven by the tests that need it)
            case RELEASING -> List.of(new FundsReserved(id), new RiskRejected(id, "VELOCITY"));
            case REVERSED -> List.of(new FundsReserved(id), new RiskRejected(id, "VELOCITY"), new FundsReleased(id));
            default -> throw new IllegalArgumentException("not reachable by replies: " + target);
        };
        replies.forEach(r -> deliver(reply(r)));
        assertThat(state(id)).as("state after advancing").isEqualTo(target.name());
    }
    // ---- reads ----

    protected long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    protected String state(UUID id) {
        return jdbc.queryForObject("select state from payment where id = ?", String.class, id);
    }

    protected long version(UUID id) {
        return jdbc.queryForObject("select version from payment where id = ?", Long.class, id);
    }

    protected int stepAttempts(UUID id) {
        return jdbc.queryForObject("select step_attempts from payment where id = ?", Integer.class, id);
    }

    /** Null when the payment has no deadline (RECEIVED and terminal states). */
    protected Instant deadline(UUID id) {
        Timestamp ts = jdbc.queryForObject("select deadline_at from payment where id = ?", Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    /** The deadline lies about {@code expected} after the moment the step ran (a second of slack either way). */
    protected void assertDeadline(UUID id, Duration expected, Instant ranAfter, Instant ranBefore) {
        Instant deadline = deadline(id);

        assertThat(deadline).as("deadline of %s", id).isNotNull();
        assertThat(deadline)
                .isAfterOrEqualTo(ranAfter.plus(expected).minusSeconds(1))
                .isBeforeOrEqualTo(ranBefore.plus(expected).plusSeconds(1));
    }

    protected long paymentRows() {
        return count("select count(*) from payment");
    }

    /** Audit rows of a payment as {@code "FROM->TO"} ({@code "null"} for the first), in no particular order. */
    protected List<String> events(UUID id) {
        return jdbc.queryForList(
                "select coalesce(from_state, 'null') || '->' || to_state from payment_event where payment_id = ?",
                String.class,
                id);
    }

    /** The failure reason stored on the event that moved the payment into {@code toState}; null for none. */
    protected String eventReason(UUID id, String toState) {
        return jdbc.queryForObject(
                "select reason from payment_event where payment_id = ? and to_state = ?", String.class, id, toState);
    }

    /** The reply's message id stored on that event; null when no reply caused it (a timeout). */
    protected UUID eventMessageId(UUID id, String toState) {
        return jdbc.queryForObject(
                "select message_id from payment_event where payment_id = ? and to_state = ?", UUID.class, id, toState);
    }

    protected long eventCount(UUID id) {
        return count("select count(*) from payment_event where payment_id = ?", id);
    }

    /** Every outbox row for this payment, decoded, oldest first. */
    protected List<Envelope> outbox(UUID id) {
        return jdbc
                .queryForList(
                        "select payload from outbox where message_key = ? order by created_at",
                        String.class,
                        id.toString())
                .stream()
                .map(codec::decode)
                .toList();
    }

    /** The outbox rows of one command type for this payment, oldest first. */
    protected List<Envelope> commands(UUID id, Class<?> type) {
        return outbox(id).stream().filter(e -> type.isInstance(e.payload())).toList();
    }

    protected long outboxCount(UUID id) {
        return count("select count(*) from outbox where message_key = ?", id.toString());
    }

    /** The single outbox row of the given message type (fails when there are none or several). */
    protected Envelope onlyOutbox(UUID id, Class<?> type) {
        List<Envelope> matching =
                outbox(id).stream().filter(e -> type.isInstance(e.payload())).toList();
        assertThat(matching).as("%s rows for %s", type.getSimpleName(), id).hasSize(1);
        return matching.get(0);
    }

    /** What a test compares before and after an action that must change nothing. */
    protected record Snapshot(List<Map<String, Object>> payments, long events, long outbox, long inbox) {}

    protected Snapshot snapshotAll() {
        return new Snapshot(
                jdbc.queryForList("select * from payment order by id"),
                count("select count(*) from payment_event"),
                count("select count(*) from outbox"),
                count("select count(*) from inbox"));
    }

    // ---- the relay, played by hand (it is switched off in these tests) ----

    /** The relay got every command to the broker: all outbox rows are sent (they stay readable). */
    protected void relayAll() {
        jdbc.update("update outbox set sent_at = now() where sent_at is null");
    }

    /** True while the payment's latest command has not been published (a broker outage, a slow relay). */
    protected long unsentCommands(UUID id) {
        return count("select count(*) from outbox where message_key = ? and sent_at is null", id.toString());
    }

    /**
     * One sweep after the relay delivered everything. The sweeper waits for an unsent command before it sends another
     * (a re-send would only pile up behind it), so tests that want a re-send publish first.
     */
    protected int sweepRelayed() {
        relayAll();
        return sweeper.sweep();
    }

    // ---- injected failures: a database trigger stands in for "the process failed at exactly this statement" ----

    /** Every UPDATE of the payment table silently affects 0 rows, as if another instance had moved it first. */
    protected void makeEveryPaymentUpdateStale() {
        jdbc.execute("create or replace function pf_skip_update() returns trigger language plpgsql as"
                + " $$ begin return null; end $$");
        jdbc.execute("create trigger pf_stale before update on payment for each row execute function pf_skip_update()");
    }

    /** Appending a message to the given topic fails, as if the process died between the state change and the append. */
    protected void makeOutboxInsertFail(String topic) {
        jdbc.execute("create or replace function pf_fail_insert() returns trigger language plpgsql as"
                + " $$ begin raise exception 'injected outbox failure'; end $$");
        jdbc.execute("create trigger pf_outbox_fail before insert on outbox for each row when (new.topic = '" + topic
                + "') execute function pf_fail_insert()");
    }

    /** Appending any message of this one payment fails: a "broken payment" next to healthy ones. */
    protected void makeOutboxInsertFailFor(UUID paymentId) {
        jdbc.execute("create or replace function pf_fail_insert_for() returns trigger language plpgsql as"
                + " $$ begin raise exception 'injected outbox failure'; end $$");
        jdbc.execute("create trigger pf_outbox_fail_for before insert on outbox for each row when (new.message_key = '"
                + paymentId + "') execute function pf_fail_insert_for()");
    }

    protected void dropFailureTriggers() {
        jdbc.execute("drop trigger if exists pf_stale on payment");
        jdbc.execute("drop trigger if exists pf_outbox_fail on outbox");
        jdbc.execute("drop trigger if exists pf_outbox_fail_for on outbox");
    }

    // ---- concurrency ----

    /**
     * Runs all tasks on their own threads, released together by a latch. A task that throws yields its Throwable in
     * the result list (tests assert which failures are allowed); a run longer than {@code limit} fails the test.
     */
    protected List<Object> concurrently(List<? extends Callable<?>> tasks, Duration limit) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return task.call();
                    } catch (Throwable e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all threads started")
                    .isTrue();
            start.countDown();
            long deadline = System.nanoTime() + limit.toNanos();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
