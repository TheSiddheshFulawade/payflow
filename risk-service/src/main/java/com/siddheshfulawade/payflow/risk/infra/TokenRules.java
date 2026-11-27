package com.siddheshfulawade.payflow.risk.infra;

import com.siddheshfulawade.payflow.contracts.Subjects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rules on top of the standard checks (signature, issuer, expiry when present), which Boot applies from the
 * {@code issuer-uri}.
 *
 * <p>Invariant (REQ-PF-003): a token is accepted only if it expires ({@code exp} present; the standard check skips a
 * token without it, which would then never expire) and its audience contains {@code payflow-api}. That audience is
 * shared by the three PayFlow services, so these rules refuse tokens issued for clients outside PayFlow; they do not
 * separate one PayFlow service from another (roles do that).
 *
 * <p>Its subject must also be usable in a command and in logs: the rule is {@link Subjects}, shared with contracts.
 */
final class TokenRules implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    TokenRules(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getExpiresAt() == null) {
            return failure("The token has no expiry");
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(audience)) {
            return failure("The token is not for this API");
        }
        if (!Subjects.isValid(jwt.getSubject())) {
            return failure("The token has no usable subject");
        }
        return OAuth2TokenValidatorResult.success();
    }

    // the description is for the DEBUG log only: SecurityProblems never sends it to the caller
    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
