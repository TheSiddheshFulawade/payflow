package com.siddheshfulawade.payflow.risk.infra;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak realm roles ({@code realm_access.roles}) as authorities, with exactly the role name: no {@code ROLE_}
 * prefix. Nothing else in the token grants anything: not a top-level {@code roles} claim, not {@code scope}. A token
 * without {@code realm_access}, or with a malformed one, simply has no authorities (403, never a 500).
 */
final class RealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess)
                || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority((String) role))
                .toList();
    }
}
