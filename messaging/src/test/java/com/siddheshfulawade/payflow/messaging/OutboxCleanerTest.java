package com.siddheshfulawade.payflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxCleanerTest extends MessagingIntegrationTest {

    @Autowired
    OutboxCleaner cleaner;

    private UUID insert(String createdAgo, String sentAgo) {
        UUID id = UUID.randomUUID();
        String sentAt = sentAgo == null ? "null" : "now() - interval '" + sentAgo + "'";
        jdbc.update(
                "insert into outbox(id, topic, message_key, payload, created_at, sent_at) values (?, 'risk.commands',"
                        + " 'k', '{}', now() - interval '" + createdAgo + "', " + sentAt + ")",
                id);
        return id;
    }

    @Test
    void REQ_PF_020_cleaner_deletes_only_sent_rows_older_than_the_cutoff() {
        UUID oldSent = insert("10 days", "10 days");
        UUID recentSent = insert("1 day", "1 day");
        UUID oldUnsent = insert("10 days", null);

        int deleted = cleaner.deleteSentOlderThan(Duration.ofDays(7));

        assertThat(deleted).isEqualTo(1);
        List<UUID> remaining = jdbc.queryForList("select id from outbox", UUID.class);
        assertThat(remaining).containsExactlyInAnyOrder(recentSent, oldUnsent).doesNotContain(oldSent);
    }
}
