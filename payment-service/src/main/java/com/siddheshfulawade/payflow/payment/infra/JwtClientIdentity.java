package com.siddheshfulawade.payflow.payment.infra;

import com.siddheshfulawade.payflow.payment.app.ClientIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The client is the {@code sub} of the verified token of this request, never a header or a body field the caller could
 * write. It scopes the Idempotency-Key and owns the payment.
 */
@Component
class JwtClientIdentity implements ClientIdentity {

    @Override
    public String clientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token
                && token.getToken().getSubject() != null) {
            return token.getToken().getSubject();
        }
        // unreachable behind SecurityConfig (every route needs a token); failing closed if it is ever wired wrongly
        throw new IllegalStateException("no authenticated client");
    }
}
