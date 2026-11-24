package com.siddheshfulawade.payflow.payment.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL and one Kafka per test JVM (singleton pattern); Ryuk removes them at exit. */
public final class Containers {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-alpine3.24");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private Containers() {}
}
