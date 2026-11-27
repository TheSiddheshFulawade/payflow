package com.siddheshfulawade.payflow.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TopicsTest {

    @Test
    void REQ_PF_020_topic_names_and_partition_count() {
        assertThat(Topics.WALLET_COMMANDS).isEqualTo("wallet.commands");
        assertThat(Topics.WALLET_REPLIES).isEqualTo("wallet.replies");
        assertThat(Topics.RISK_COMMANDS).isEqualTo("risk.commands");
        assertThat(Topics.RISK_REPLIES).isEqualTo("risk.replies");
        assertThat(Topics.PARTITIONS).isEqualTo(3);
    }

    @Test
    void REQ_PF_020_dead_letter_topic_appends_dlt() {
        assertThat(Topics.deadLetter("wallet.commands")).isEqualTo("wallet.commands.dlt");
    }

    @Test
    void REQ_PF_020_every_message_goes_to_its_services_topic() {
        java.util.UUID p = Samples.PAYMENT;
        Map<Message, String> expected = Map.ofEntries(
                Map.entry(new ReserveFunds(p, "a", 1, "INR", "REQ"), Topics.WALLET_COMMANDS),
                Map.entry(new CaptureFunds(p, "a", "b", 1, "INR"), Topics.WALLET_COMMANDS),
                Map.entry(new ReleaseFunds(p, "a", 1), Topics.WALLET_COMMANDS),
                Map.entry(new CheckRisk(p, "a", 1, "INR"), Topics.RISK_COMMANDS),
                Map.entry(new FundsReserved(p), Topics.WALLET_REPLIES),
                Map.entry(new FundsRejected(p, "r"), Topics.WALLET_REPLIES),
                Map.entry(new FundsCaptured(p), Topics.WALLET_REPLIES),
                Map.entry(new CaptureFailed(p, "r"), Topics.WALLET_REPLIES),
                Map.entry(new FundsReleased(p), Topics.WALLET_REPLIES),
                Map.entry(new RiskApproved(p), Topics.RISK_REPLIES),
                Map.entry(new RiskRejected(p, "r"), Topics.RISK_REPLIES));

        assertThat(expected).hasSize(11);
        expected.forEach((message, topic) -> assertThat(Topics.topicFor(message))
                .as(message.getClass().getSimpleName())
                .isEqualTo(topic));
    }
}
