package com.siddheshfulawade.payflow.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hazelcast.core.HazelcastInstance;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.risk.app.DailyTotals;
import com.siddheshfulawade.payflow.risk.app.RiskCommandHandler;
import com.siddheshfulawade.payflow.risk.app.RiskDecisions;
import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Smoke test of the web layer only: it runs in the default build without Docker, so the database, Kafka and the
// messaging library (outbox, relay, inbox) are switched off here, and the Hazelcast member is a mock (no cluster
// is started). The service tests with PostgreSQL and Kafka are Testcontainers tests (tag "it"); the two-member
// Hazelcast tests start their own members.
@SpringBootTest(
        classes = RiskServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "com.siddheshfulawade.payflow.messaging.MessagingAutoConfiguration")
@AutoConfigureMockMvc
class RiskServiceApplicationHealthTest {

    // Beans of the risk packages that need the database, Kafka or a Hazelcast cluster (all switched off or
    // mocked here). If a new bean needs one of them, mock it here too.
    @MockitoBean
    HazelcastInstance hazelcast;

    @MockitoBean
    VelocityCounter velocityCounter;

    @MockitoBean
    RiskDecisions decisions;

    @MockitoBean
    DailyTotals dailyTotals;

    @MockitoBean
    RiskCommandHandler commandHandler;

    @MockitoBean
    IdempotentConsumer idempotentConsumer;

    @MockitoBean
    MessageCodec messageCodec;

    @Autowired
    MockMvc mvc;

    @Autowired
    Environment environment;

    @Test
    void healthEndpointReportsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void serviceListensOnItsDocumentedPort() {
        assertThat(environment.getProperty("server.port")).isEqualTo("8083");
    }
}
