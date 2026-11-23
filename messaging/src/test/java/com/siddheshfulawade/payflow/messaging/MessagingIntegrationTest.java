package com.siddheshfulawade.payflow.messaging;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.messaging.support.Containers;
import com.siddheshfulawade.payflow.messaging.support.KafkaProbe;
import com.siddheshfulawade.payflow.messaging.support.SwitchableRelayHook;
import com.siddheshfulawade.payflow.messaging.support.TestApplication;
import com.siddheshfulawade.payflow.messaging.support.TestEffect;
import com.siddheshfulawade.payflow.messaging.support.WalletCommandListener;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared context: real PostgreSQL and Kafka, relay scheduling off so tests call relayOnce() themselves. */
@Tag("it")
@SpringBootTest(classes = TestApplication.class)
abstract class MessagingIntegrationTest {

    static final Duration TIMEOUT = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected OutboxWriter outbox;

    @Autowired
    protected OutboxRelay relay;

    @Autowired
    protected SwitchableRelayHook hook;

    @Autowired
    protected TestEffect effect;

    @Autowired
    protected WalletCommandListener listener;

    protected final KafkaProbe kafka = new KafkaProbe();

    @BeforeEach
    void cleanState() {
        jdbc.execute("truncate table outbox, inbox, effect");
        hook.reset();
        effect.reset();
    }

    /** Appends to the outbox in its own committed transaction and returns the outbox id. */
    protected UUID appendCommitted(Envelope envelope) {
        return inTransaction(() -> outbox.append(envelope));
    }

    protected <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    protected int effectRows(UUID paymentId) {
        return jdbc.queryForObject("select count(*) from effect where payment_id = ?", Integer.class, paymentId);
    }

    protected int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    protected boolean isSent(UUID outboxId) {
        return jdbc.queryForObject("select sent_at is not null from outbox where id = ?", Boolean.class, outboxId);
    }
}
