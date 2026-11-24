package com.siddheshfulawade.payflow.messaging;

import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.contracts.PoisonMessageException;
import com.siddheshfulawade.payflow.contracts.Topics;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

/** Outbox, relay, inbox and the Kafka client settings, for every service that has this jar on its classpath. */
@AutoConfiguration(
        after = {
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcClientAutoConfiguration.class,
            KafkaAutoConfiguration.class
        })
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
public class MessagingAutoConfiguration {

    /** Pause between deliveries of a record whose handler failed (poison and sender-retried failures have none). */
    static final long RETRY_INTERVAL_MS = 500;

    /**
     * Retries after the first failure: 10 deliveries over about 5 s. Enough to ride out a short database blip
     * (failover, lock timeout, full pool) without blocking the partition for long. A longer outage is not fixed by
     * retrying here: the record goes to the dead-letter topic, where it is visible, and the saga's deadline and
     * sweeper re-send the command (handlers are idempotent per payment), so a parked record does not leave a
     * payment stuck.
     */
    static final long RETRIES = 9;

    @Bean
    @ConditionalOnMissingBean
    MessageCodec messageCodec() {
        return new MessageCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    RelayHook noOpRelayHook() {
        return outboxIds -> {};
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, MessageCodec codec) {
        return new OutboxWriter(jdbc, codec);
    }

    @Bean
    OutboxRelay outboxRelay(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            KafkaTemplate<String, String> kafka,
            RelayHook hook,
            MessagingProperties properties) {
        MessagingProperties.Relay relay = properties.relay();
        return new OutboxRelay(jdbc, transactionManager, kafka, hook, relay.batchSize(), relay.ackTimeout());
    }

    @Bean
    IdempotentConsumer idempotentConsumer(
            JdbcClient jdbc, PlatformTransactionManager transactionManager, OutboxWriter outbox) {
        return new IdempotentConsumer(jdbc, transactionManager, outbox);
    }

    @Bean
    OutboxCleaner outboxCleaner(JdbcClient jdbc) {
        return new OutboxCleaner(jdbc);
    }

    @Bean
    MessagingSchedule messagingSchedule(OutboxRelay relay, OutboxCleaner cleaner, MessagingProperties properties) {
        return new MessagingSchedule(relay, cleaner, properties);
    }

    @Bean
    DefaultKafkaProducerFactoryCustomizer payflowProducerSettings() {
        return factory -> factory.updateConfigs(KafkaClientSettings.updates(
                factory.getConfigurationProperties(),
                KafkaClientSettings.PRODUCER_DEFAULTS,
                KafkaClientSettings.PRODUCER_REQUIRED));
    }

    @Bean
    DefaultKafkaConsumerFactoryCustomizer payflowConsumerSettings() {
        return factory -> factory.updateConfigs(KafkaClientSettings.updates(
                factory.getConfigurationProperties(),
                KafkaClientSettings.CONSUMER_DEFAULTS,
                KafkaClientSettings.CONSUMER_REQUIRED));
    }

    /** Boot's listener container factory applies this to every container it creates. */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> payflowAckMode() {
        // RECORD: the offset is committed right after the listener returned, i.e. after its DB commit (REQ-PF-022).
        // A crash before that redelivers the record; the inbox turns it into a no-op.
        return container -> container.getContainerProperties().setAckMode(AckMode.RECORD);
    }

    /** Boot's listener container factory picks up this error handler. */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    CommonErrorHandler payflowErrorHandler(KafkaTemplate<String, String> kafka) {
        // partition -1: let the producer pick it by key, so a parked payment's records stay together
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(
                kafka, (record, exception) -> new TopicPartition(Topics.deadLetter(record.topic()), -1));
        // no stack trace header: it carries every cause's message, i.e. library text that may quote the record's
        // values. The exception class and our log-safe message (PoisonMessageException) are enough to triage.
        deadLetter.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        // If publishing to the dead-letter topic fails (broker down, no ack), the recoverer throws, the error handler
        // seeks back and the record is delivered again: its offset is not committed, so nothing is lost.
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetter, new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
        // Parked at once instead of blocking the partition: a poison message fails the same way every time, and a
        // message its sender re-sends is not lost.
        handler.addNotRetryableExceptions(PoisonMessageException.class, RetriedBySenderException.class);
        return handler;
    }

    /** Topics are declared here (broker auto-creation is off), so a misspelt name fails instead of appearing. */
    @Bean
    KafkaAdmin.NewTopics payflowTopics(MessagingProperties properties) {
        int replicas = properties.replicas();
        // acks=all only protects data if at least 2 replicas must have it; keep one spare for broker restarts
        String minInSync = String.valueOf(Math.max(1, replicas - 1));
        List<String> topics =
                List.of(Topics.WALLET_COMMANDS, Topics.WALLET_REPLIES, Topics.RISK_COMMANDS, Topics.RISK_REPLIES);
        NewTopic[] all = topics.stream()
                .flatMap(topic -> Stream.of(topic, Topics.deadLetter(topic)))
                .map(name -> TopicBuilder.name(name)
                        .partitions(Topics.PARTITIONS)
                        .replicas(replicas)
                        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInSync)
                        .build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(all);
    }
}
