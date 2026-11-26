package com.siddheshfulawade.payflow.risk.support;

import com.hazelcast.core.HazelcastInstance;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.risk.RiskServiceApplication;
import com.siddheshfulawade.payflow.risk.app.DailyTotals;
import com.siddheshfulawade.payflow.risk.app.RiskCommandHandler;
import com.siddheshfulawade.payflow.risk.app.RiskDecisions;
import com.siddheshfulawade.payflow.risk.app.VelocityCounter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole web layer with the service's real security configuration and no Docker: database, Kafka and messaging are
 * switched off, and the Hazelcast member and the application beans are mocks. The resource server trusts the keys of
 * {@link TestIssuer}. No default token: a request without one must be refused.
 */
@SpringBootTest(
        classes = RiskServiceApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "com.siddheshfulawade.payflow.messaging.MessagingAutoConfiguration")
@AutoConfigureMockMvc
public abstract class SecuredWebTest {

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestIssuer.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestIssuer::jwksUri);
    }

    @MockitoBean
    protected HazelcastInstance hazelcast;

    @MockitoBean
    protected VelocityCounter velocityCounter;

    @MockitoBean
    protected RiskDecisions decisions;

    @MockitoBean
    protected DailyTotals dailyTotals;

    @MockitoBean
    protected RiskCommandHandler commandHandler;

    @MockitoBean
    protected IdempotentConsumer idempotentConsumer;

    @MockitoBean
    protected MessageCodec messageCodec;

    @Autowired
    protected MockMvc mvc;
}
