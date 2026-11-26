package com.siddheshfulawade.payflow.payment.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * For the {@code @WebMvcTest} slices that test validation and error bodies, not security: every request is sent as a
 * customer with both payment roles (subject {@code local}, the client id the old tests used), so they keep their
 * meaning now that the API needs a token. A test that checks security extends {@code SecuredWebTest} and sends no
 * default token. csrf() is harmless for a stateless API and keeps a slice that does not load the service's own
 * security configuration working too.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JwtDefaults {

    @Bean
    MockMvcBuilderCustomizer defaultCustomer() {
        return builder ->
                builder.defaultRequest(get("/").with(Tokens.customer("local")).with(csrf()));
    }
}
