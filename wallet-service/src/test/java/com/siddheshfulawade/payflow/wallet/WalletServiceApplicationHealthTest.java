package com.siddheshfulawade.payflow.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.wallet.app.Holds;
import com.siddheshfulawade.payflow.wallet.app.WalletAdminService;
import com.siddheshfulawade.payflow.wallet.app.WalletCommandHandler;
import com.siddheshfulawade.payflow.wallet.app.Wallets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Smoke test of the web layer only: it runs in the default build without Docker, so the database and the
// messaging library (outbox, relay, inbox) are switched off here. The service tests with PostgreSQL and Kafka
// are Testcontainers tests (tag "it").
@SpringBootTest(
        classes = WalletServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "com.siddheshfulawade.payflow.messaging.MessagingAutoConfiguration")
@AutoConfigureMockMvc
class WalletServiceApplicationHealthTest {

    // Beans of the wallet packages that need the database or Kafka (switched off above) are replaced by mocks, so
    // this stays a fast test without Docker.
    @MockitoBean
    Holds holds;

    @MockitoBean
    Wallets wallets;

    @MockitoBean
    WalletAdminService adminService;

    @MockitoBean
    WalletCommandHandler commandHandler;

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
        assertThat(environment.getProperty("server.port")).isEqualTo("8082");
    }
}
