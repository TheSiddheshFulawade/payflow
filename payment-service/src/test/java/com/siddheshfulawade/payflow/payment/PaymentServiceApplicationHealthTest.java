package com.siddheshfulawade.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

// Smoke test of the web layer only: it runs in the default build without Docker, so the database and the
// messaging library (outbox, relay, inbox) are switched off here. The service tests with PostgreSQL and Kafka
// are Testcontainers tests (tag "it").
@SpringBootTest(
        classes = PaymentServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "com.siddheshfulawade.payflow.messaging.MessagingAutoConfiguration")
@AutoConfigureMockMvc
class PaymentServiceApplicationHealthTest {

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
        assertThat(environment.getProperty("server.port")).isEqualTo("8081");
    }
}
