package com.siddheshfulawade.payflow.wallet.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A stand-in for the Keycloak realm in tests that need no Docker: it serves a JWK set on loopback and signs tokens the
 * way Keycloak shapes them (issuer, audience list, {@code realm_access.roles}). The service under test is pointed at
 * it with the standard {@code spring.security.oauth2.resourceserver.jwt.*} properties.
 */
public final class TestIssuer {

    public static final String ISSUER = "http://keycloak.test/realms/payflow";
    public static final String AUDIENCE = "payflow-api";

    private static final RSAKey KEY = generate();
    private static final RSAKey STRANGER_KEY = generate(); // same key id, other key: a forged signature
    private static final HttpServer SERVER = start();

    private TestIssuer() {}

    public static String jwksUri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/jwks";
    }

    /** A token that a correctly configured resource server accepts. */
    public static String valid(String subject, String... roles) {
        return sign(
                KEY,
                claims(
                        subject,
                        ISSUER,
                        List.of(AUDIENCE),
                        List.of(roles),
                        Instant.now().plusSeconds(300)));
    }

    public static String token(
            String subject, String issuer, List<String> audience, List<String> roles, Instant expiresAt) {
        return sign(KEY, claims(subject, issuer, audience, roles, expiresAt));
    }

    /** Roles in the wrong places (a top-level claim and a scope): they must NOT become authorities. */
    public static String withRolesOutsideRealmAccess(String subject, String... roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(claims(
                        subject,
                        ISSUER,
                        List.of(AUDIENCE),
                        List.of(),
                        Instant.now().plusSeconds(300)))
                .claim("roles", List.of(roles))
                .claim("scope", String.join(" ", roles))
                .build();
        return sign(KEY, claims);
    }

    /** Valid in every claim, but signed by a key the JWK set does not contain. */
    public static String signedByStranger(String subject, String... roles) {
        return sign(
                STRANGER_KEY,
                claims(
                        subject,
                        ISSUER,
                        List.of(AUDIENCE),
                        List.of(roles),
                        Instant.now().plusSeconds(300)));
    }

    /** An unsigned token ({@code alg: none}) with otherwise perfect claims. */
    public static String unsigned(String subject, String... roles) {
        return new PlainJWT(claims(
                        subject,
                        ISSUER,
                        List.of(AUDIENCE),
                        List.of(roles),
                        Instant.now().plusSeconds(300)))
                .serialize();
    }

    /** Perfect in every claim but without {@code exp}: a token that never expires must be refused. */
    public static String withoutExpiry(String subject, String... roles) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(claims(
                        subject,
                        ISSUER,
                        List.of(AUDIENCE),
                        List.of(roles),
                        Instant.now().plusSeconds(300)))
                .expirationTime(null)
                .build();
        return sign(KEY, claims);
    }

    private static JWTClaimsSet claims(
            String subject, String issuer, List<String> audience, List<String> roles, Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(expiresAt))
                .claim("realm_access", java.util.Map.of("roles", roles));
        if (!audience.isEmpty()) {
            claims.audience(audience);
        }
        return claims.build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign the test token", e);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "test-jwks");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
