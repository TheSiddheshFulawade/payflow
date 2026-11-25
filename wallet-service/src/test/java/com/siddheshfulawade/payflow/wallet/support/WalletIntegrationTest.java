package com.siddheshfulawade.payflow.wallet.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.CaptureFunds;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.ReleaseFunds;
import com.siddheshfulawade.payflow.contracts.ReserveFunds;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.wallet.WalletServiceApplication;
import com.siddheshfulawade.payflow.wallet.app.WalletCommandHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared context for the wallet integration tests: real PostgreSQL and Kafka, the relay switched off (tests call
 * relayOnce() when they need Kafka). Commands normally reach the handler the way production does it:
 * {@code IdempotentConsumer.handle(envelope, handler::handle)}.
 */
@Tag("it")
@SpringBootTest(classes = WalletServiceApplication.class)
@AutoConfigureMockMvc
public abstract class WalletIntegrationTest {

    public static final Duration TIMEOUT = Duration.ofSeconds(60);

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
    protected IdempotentConsumer consumer;

    @Autowired
    protected WalletCommandHandler handler;

    protected final MessageCodec codec = new MessageCodec();
    protected final KafkaProbe kafka = new KafkaProbe();

    @BeforeEach
    void cleanState() {
        // TRUNCATE locks in the listed order: the order the command handler locks (inbox, hold, wallet, ledger, outbox)
        jdbc.execute("truncate table inbox, hold, wallet, ledger_entry, outbox");
    }

    // ---- commands (a fresh message id each time) ----

    protected static Envelope reserve(UUID payment, String payer, long amount) {
        return reserve(payment, payer, amount, 1);
    }

    protected static Envelope reserve(UUID payment, String payer, long amount, int attempt) {
        return Envelope.of(new ReserveFunds(payment, payer, amount, "INR"), attempt, Clock.systemUTC());
    }

    protected static Envelope release(UUID payment, String payer, long amount) {
        return release(payment, payer, amount, 1);
    }

    protected static Envelope release(UUID payment, String payer, long amount, int attempt) {
        return Envelope.of(new ReleaseFunds(payment, payer, amount), attempt, Clock.systemUTC());
    }

    protected static Envelope capture(UUID payment, String payer, String payee, long amount) {
        return capture(payment, payer, payee, amount, 1);
    }

    protected static Envelope capture(UUID payment, String payer, String payee, long amount, int attempt) {
        return Envelope.of(new CaptureFunds(payment, payer, payee, amount, "INR"), attempt, Clock.systemUTC());
    }

    /** The same command as a re-send by the sweeper: same payload, NEW message id (so the inbox does not catch it). */
    protected static Envelope resent(Envelope command) {
        return new Envelope(
                UUID.randomUUID(),
                command.paymentId(),
                command.type(),
                command.version(),
                command.attempt(),
                command.occurredAt(),
                command.payload());
    }

    /** Same as {@link #resent} but on another saga attempt. */
    protected static Envelope resentOnAttempt(Envelope command, int attempt) {
        return new Envelope(
                UUID.randomUUID(),
                command.paymentId(),
                command.type(),
                command.version(),
                attempt,
                command.occurredAt(),
                command.payload());
    }

    // ---- running a command ----

    /** Runs the command through the idempotent consumer, as the Kafka listener does, and returns the stored reply. */
    protected Envelope apply(Envelope command) {
        consumer.handle(command, handler::handle);
        return storedReply(command);
    }

    /**
     * A poison command is thrown out of the consumer and leaves no trace: no inbox row, no reply in the outbox, and
     * the message names no payer or amount.
     */
    protected void assertPoison(Envelope command, String... mustNotAppear) {
        long inbox = jdbc.queryForObject("select count(*) from inbox", Long.class);
        long outbox = jdbc.queryForObject("select count(*) from outbox", Long.class);

        assertThatThrownBy(() -> consumer.handle(command, handler::handle))
                .isInstanceOf(PoisonMessageException.class)
                .message()
                .doesNotContain(mustNotAppear);

        assertThat(jdbc.queryForObject("select count(*) from inbox", Long.class))
                .as("inbox rolled back")
                .isEqualTo(inbox);
        assertThat(jdbc.queryForObject("select count(*) from outbox", Long.class))
                .as("no reply queued")
                .isEqualTo(outbox);
    }

    /** The reply the inbox row of this command points to (null-safe: fails the test when there is none). */
    protected Envelope storedReply(Envelope command) {
        List<String> payloads = jdbc.queryForList("""
                        select o.payload from inbox i join outbox o on o.id = i.reply_outbox_id
                        where i.message_id = ?""", String.class, command.messageId());
        assertThat(payloads).as("stored reply of %s", command.type()).hasSize(1);
        return codec.decode(payloads.get(0));
    }

    /** Every reply row in the outbox for this payment, decoded, oldest first. */
    protected List<Envelope> outboxReplies(UUID payment) {
        return jdbc
                .queryForList(
                        "select payload from outbox where topic = 'wallet.replies' and message_key = ?"
                                + " order by created_at",
                        String.class,
                        payment.toString())
                .stream()
                .map(codec::decode)
                .toList();
    }

    // ---- fixtures and reads ----

    protected void wallet(String id, long available) {
        wallet(id, available, 0, "ACTIVE");
    }

    protected void wallet(String id, long available, String status) {
        wallet(id, available, 0, status);
    }

    protected void wallet(String id, long available, long held, String status) {
        jdbc.update(
                "insert into wallet(id, owner_sub, available, held, status, version) values (?, ?, ?, ?, ?, 0)",
                id,
                "owner-" + id,
                available,
                held,
                status);
    }

    protected long available(String wallet) {
        return jdbc.queryForObject("select available from wallet where id = ?", Long.class, wallet);
    }

    protected long held(String wallet) {
        return jdbc.queryForObject("select held from wallet where id = ?", Long.class, wallet);
    }

    protected void assertBalances(String wallet, long available, long held) {
        assertThat(List.of(available(wallet), held(wallet)))
                .as("[available, held] of %s", wallet)
                .containsExactly(available, held);
    }

    /** RESERVED, RELEASED, CAPTURED, REJECTED, or "NONE" when the payment has no hold row. */
    protected String holdState(UUID payment) {
        List<String> state = jdbc.queryForList("select state from hold where payment_id = ?", String.class, payment);
        return state.isEmpty() ? "NONE" : state.get(0);
    }

    protected String holdReason(UUID payment) {
        return jdbc.queryForObject("select reason from hold where payment_id = ?", String.class, payment);
    }

    protected int holdRows(UUID payment) {
        return jdbc.queryForObject("select count(*) from hold where payment_id = ?", Integer.class, payment);
    }

    /** Ledger rows of a payment as {@code "KIND wallet amount"}. */
    protected List<String> ledger(UUID payment) {
        return jdbc.queryForList(
                "select kind || ' ' || wallet_id || ' ' || amount from ledger_entry where payment_id = ?",
                String.class,
                payment);
    }

    protected long totalMoney() {
        return jdbc.queryForObject("select coalesce(sum(available + held), 0) from wallet", Long.class);
    }

    protected long totalHeld() {
        return jdbc.queryForObject("select coalesce(sum(held), 0) from wallet", Long.class);
    }

    // ---- concurrency ----

    /**
     * Runs all tasks on their own threads, released together by a latch, and returns their results in task order.
     * Any task exception (deadlock, constraint violation) or a run longer than {@code limit} fails the test.
     */
    protected <T> List<T> concurrently(List<? extends Callable<T>> tasks, Duration limit) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all threads started")
                    .isTrue();
            start.countDown();
            long deadline = System.nanoTime() + limit.toNanos();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
