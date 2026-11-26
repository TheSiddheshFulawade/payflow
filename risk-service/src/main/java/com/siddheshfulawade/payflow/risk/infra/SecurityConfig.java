package com.siddheshfulawade.payflow.risk.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * The risk service has no business endpoints; it is an OAuth 2.0 resource server all the same. Only
 * {@code GET /actuator/health} is open; everything else, even a path that does not exist, needs a valid token
 * (signature, issuer and expiry are checked by Boot from {@code spring.security.oauth2.resourceserver.jwt.*}; the
 * audience by {@link TokenRules}).
 *
 * <p>Invariant (REQ-PF-003): authentication and the role are decided here, in the filter chain, before the request
 * body is read or validated, so a caller without rights learns nothing about the API and nothing is stored.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProblems problems) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        return http
                // bearer tokens only: no cookie, no session, so there is nothing for CSRF to forge
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        .anyRequest()
                        // no rule names it (other method or path): 401 without a token, else 403
                        .denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
    }

    /**
     * One bean for all three jobs (401, 403, 400). Spring Security finds it by type as the
     * {@link RequestRejectedHandler} of its firewall; a second bean of that type would make the lookup ambiguous and
     * silently bring back the default (an empty 400).
     */
    @Bean
    SecurityProblems securityProblems() {
        return new SecurityProblems();
    }

    /** Boot adds every {@code OAuth2TokenValidator<Jwt>} bean to the standard checks of its decoder. */
    @Bean
    OAuth2TokenValidator<Jwt> tokenRules(@Value("${payflow.security.audience:payflow-api}") String audience) {
        return new TokenRules(audience);
    }
}
