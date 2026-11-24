package com.siddheshfulawade.payflow.risk.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.payflow.contracts.CheckRisk;
import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.risk.RiskServiceApplication;
import com.siddheshfulawade.payflow.risk.app.RiskCommandHandler;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Shared context for the risk integration tests: real PostgreSQL and Kafka, one real Hazelcast member (its own
 * cluster name and port), the relay switched off (tests call relayOnce() when they need Kafka). Commands normally
 * reach the handler the way production does it: {@code IdempotentConsumer.handle(envelope, handler::handle)}.
 *
 * <p>Every test uses its own payer ({@link #newPayer()}), so velocity entries left in Hazelcast by an earlier test
 * cannot matter. The velocity port and the clock are test doubles ({@link RiskTestBeans}).
 */
@Tag("it")
@SpringBootTest(classes = {RiskServiceApplication.class, RiskTestBeans.class})
public abstract class RiskIntegrationTest {

    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final int HAZELCAST_PORT = freePort();

    @DynamicPropertySource
    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", Containers.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.flyway.locations", () -> "classpath:db/messaging,classpath:db/migration");
        registry.add("payflow.messaging.relay.enabled", () -> "false");
        // a cluster of one: this test JVM's member must not join anything else on the machine
        registry.add("payflow.risk.hazelcast.cluster-name", () -> "risk-it-" + UUID.randomUUID());
        registry.add("payflow.risk.hazelcast.port", () -> String.valueOf(HAZELCAST_PORT));
        registry.add("payflow.risk.hazelcast.members[0]", () -> "127.0.0.1:" + HAZELCAST_PORT);
        registry.add("payflow.risk.hazelcast.bind-address", () -> "127.0.0.1");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected IdempotentConsumer consumer;

    @Autowired
    protected RiskCommandHandler handler;

    @Autowired
    protected ControllableVelocityCounter velocity;

    @Autowired
    protected MutableClock clock;

    protected final MessageCodec codec = new MessageCodec();
    protected final KafkaProbe kafka = new KafkaProbe();

    @BeforeEach
    void cleanState() {
        jdbc.execute("truncate table outbox, inbox, risk_decision, daily_total");
        velocity.reset();
        clock.useSystemTime();
    }

    // ---- commands (a fresh message id each time) ----

    /** A payer name no other test uses (Hazelcast keeps its entries across tests). */
    protected static String newPayer() {
        return "payer-" + UUID.randomUUID().toString().substring(0, 12);
    }

    protected static Envelope check(UUID payment, String payer, long amount) {
        return check(payment, payer, amount, 1);
    }

    protected static Envelope check(UUID payment, String payer, long amount, int attempt) {
        return Envelope.of(new CheckRisk(payment, payer, amount, "INR"), attempt, Clock.systemUTC());
    }

    /** The same command as a re-send by the sweeper: same payload, NEW message id (so the inbox does not catch it). */
    protected static Envelope resent(Envelope command) {
        return resentOnAttempt(command, command.attempt());
    }

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

    /** A new payment of this payer, applied. */
    protected Envelope pay(String payer, long amount) {
        return apply(check(UUID.randomUUID(), payer, amount));
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

    /** The reply the inbox row of this command points to (fails the test when there is none). */
    protected Envelope storedReply(Envelope command) {
        List<String> payloads = jdbc.queryForList("""
                        select o.payload from inbox i join outbox o on o.id = i.reply_outbox_id
                        where i.message_id = ?""", String.class, command.messageId());
        assertThat(payloads).as("stored reply of %s", command.type()).hasSize(1);
        return codec.decode(payloads.get(0));
    }

    protected long outboxRows() {
        return jdbc.queryForObject("select count(*) from outbox", Long.class);
    }

    protected long inboxRows() {
        return jdbc.queryForObject("select count(*) from inbox", Long.class);
    }

    // ---- reads ----

    protected int decisionRows(UUID payment) {
        return jdbc.queryForObject("select count(*) from risk_decision where payment_id = ?", Integer.class, payment);
    }

    /** The decision row of a payment: payer, amount, decision, rule. Fails when there is none. */
    protected Map<String, Object> decision(UUID payment) {
        return jdbc.queryForMap(
                "select payer, amount, decision, rule from risk_decision where payment_id = ?", payment);
    }

    protected LocalDate today() {
        return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** The payer's approved total of a UTC business date; 0 when there is no row. */
    protected long dailyTotal(String payer, LocalDate date) {
        List<Long> rows = jdbc.queryForList(
                "select amount from daily_total where payer = ? and business_date = ?", Long.class, payer, date);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    protected long dailyTotal(String payer) {
        return dailyTotal(payer, today());
    }

    protected int dailyTotalRows(String payer) {
        return jdbc.queryForObject("select count(*) from daily_total where payer = ?", Integer.class, payer);
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

    protected Callable<Envelope> run(Envelope command) {
        return () -> {
            consumer.handle(command, handler::handle);
            return storedReply(command);
        };
    }
}
