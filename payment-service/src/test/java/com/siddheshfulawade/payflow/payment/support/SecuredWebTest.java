package com.siddheshfulawade.payflow.payment.support;

import com.siddheshfulawade.payflow.contracts.MessageCodec;
import com.siddheshfulawade.payflow.messaging.IdempotentConsumer;
import com.siddheshfulawade.payflow.payment.PaymentServiceApplication;
import com.siddheshfulawade.payflow.payment.app.PaymentService;
import com.siddheshfulawade.payflow.payment.app.PaymentSweeper;
import com.siddheshfulawade.payflow.payment.app.Payments;
import com.siddheshfulawade.payflow.payment.app.ReplyHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole web layer with the service's real security configuration and no Docker: database, Kafka and messaging are
 * switched off and the application beans behind the controllers are mocks. The resource server trusts the keys of
 * {@link TestIssuer}, so a test can send a real signed token or a {@code jwt()} post-processor. No default token: a
 * request without one must be refused.
 */
@SpringBootTest(
        classes = PaymentServiceApplication.class,
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
    protected Payments payments;

    @MockitoBean
    protected PaymentService service;

    @MockitoBean
    protected ReplyHandler replyHandler;

    @MockitoBean
    protected PaymentSweeper paymentSweeper;

    @MockitoBean
    protected IdempotentConsumer idempotentConsumer;

    @MockitoBean
    protected MessageCodec messageCodec;

    @Autowired
    protected MockMvc mvc;
}
