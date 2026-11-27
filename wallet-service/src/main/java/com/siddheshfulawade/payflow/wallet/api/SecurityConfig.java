package com.siddheshfulawade.payflow.wallet.api;

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
import org.springframework.security.oauth2.server.resource.web.OAuth2ProtectedResourceMetadataFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * The wallet API is an OAuth 2.0 resource server. Only {@code GET /actuator/health} is open; everything else, even a
 * path that does not exist, is refused (401 without a valid token, 403 with one). Signature, issuer and expiry are checked by Boot from
 * {@code spring.security.oauth2.resourceserver.jwt.*}; the audience and the subject by {@link TokenRules}.
 *
 * <p>Invariant (REQ-PF-003): authentication and the role are decided here, in the filter chain, before the request
 * body is read or validated, so a caller without rights learns nothing about the API and nothing is stored.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String OPS = "ops";
    static final String READ = "payments:read";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProblems problems) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        DefaultSecurityFilterChain chain = http
                // bearer tokens only: no cookie, no session, so there is nothing for CSRF to forge
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        // each ops route names its own method; any other method or /api/v1/ops/** path falls to denyAll
                        .requestMatchers(HttpMethod.POST, "/api/v1/ops/wallets")
                        .hasAuthority(OPS)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/ops/wallets/*/status")
                        .hasAuthority(OPS)
                        // customers read their own wallet (WalletController checks the owner); ops read any
                        .requestMatchers(HttpMethod.GET, "/api/v1/wallets/*")
                        .hasAnyAuthority(READ, OPS)
                        .anyRequest()
                        // no rule names it (other method or path): 401 without a token, else 403
                        .denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .build();
        // Spring Security 7 adds OAuth2ProtectedResourceMetadataFilter to every resource server, before authorization,
        // and the configurer has no switch to turn it off. Without this line it would answer
        // /.well-known/oauth-protected-resource to anyone with 200. Removed, that path is just an unknown path:
        // denyAll.
        chain.getFilters().removeIf(OAuth2ProtectedResourceMetadataFilter.class::isInstance);
        return chain;
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
