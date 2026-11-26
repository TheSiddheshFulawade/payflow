package com.siddheshfulawade.payflow.wallet.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Arrays;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Authenticated callers for MockMvc: roles become authorities with exactly the realm role name (no ROLE_ prefix). */
public final class Tokens {

    public static final String WRITE = "payments:write";
    public static final String READ = "payments:read";
    public static final String OPS = "ops";

    private Tokens() {}

    /** A caller with the given subject and realm roles. */
    public static JwtRequestPostProcessor as(String subject, String... roles) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(
                        Arrays.stream(roles).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    /** A customer who may create and read payments. */
    public static JwtRequestPostProcessor customer(String subject) {
        return as(subject, WRITE, READ);
    }
}
