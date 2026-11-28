package com.siddheshfulawade.payflow.recon.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL per test JVM (singleton pattern, as in the services); Ryuk removes it at exit. */
public final class ReconPostgres {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-alpine3.24");

    static {
        POSTGRES.start();
    }

    private ReconPostgres() {}

    /** Points the datasource at the container and keeps the job from starting by itself. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.batch.job.enabled", () -> "false");
        // the application has no default token and refuses to start without one; this is a test-only value
        registry.add("payflow.recon.ops-token", () -> "test-only-token");
    }
}
