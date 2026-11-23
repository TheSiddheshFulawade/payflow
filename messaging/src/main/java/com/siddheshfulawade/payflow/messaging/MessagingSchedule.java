package com.siddheshfulawade.payflow.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Runs the relay every {@code payflow.messaging.relay.interval} (when enabled) and the outbox cleaner daily. */
public class MessagingSchedule implements SchedulingConfigurer {

    /** Every day at 03:30, a quiet hour. */
    static final String CLEANUP_CRON = "0 30 3 * * *";

    private static final Logger log = LoggerFactory.getLogger(MessagingSchedule.class);

    private final OutboxRelay relay;
    private final OutboxCleaner cleaner;
    private final MessagingProperties.Relay settings;

    public MessagingSchedule(OutboxRelay relay, OutboxCleaner cleaner, MessagingProperties properties) {
        this.relay = relay;
        this.cleaner = cleaner;
        this.settings = properties.relay();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (settings.enabled()) {
            // fixed delay, not fixed rate: runs never overlap within one instance (SKIP LOCKED covers other ones)
            registrar.addFixedDelayTask(this::relayUntilEmpty, settings.interval());
        }
        registrar.addCronTask(this::cleanUp, CLEANUP_CRON);
    }

    void relayUntilEmpty() {
        try {
            int sent;
            do {
                sent = relay.relayOnce();
            } while (sent == settings.batchSize()); // a full batch means more may be waiting
        } catch (RuntimeException e) {
            log.warn("outbox relay run failed; the rows stay unsent and the next run retries them", e);
        }
    }

    void cleanUp() {
        int deleted = cleaner.deleteSentOlderThan(OutboxCleaner.RETENTION);
        log.info("outbox cleaner deleted {} sent rows older than {}", deleted, OutboxCleaner.RETENTION);
    }
}
