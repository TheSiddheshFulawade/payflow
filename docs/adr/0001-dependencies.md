# ADR-0001 · Runtime and test dependencies

Status: living document. Every dependency is listed here before it's added to a pom. Versions are filled in at
PF-01 from the current releases (checked with context7), aligned with settlement-lab where they overlap
(Spring Boot BOM, JUnit, jqwik, ArchUnit, Spotless, JaCoCo).

| Dependency                                                            | Scope   | Module(s)              | Why                                                                                                                          | Version                   |
| --------------------------------------------------------------------- | ------- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------- |
| Spring Boot (web, validation, actuator, jdbc, oauth2-resource-server) | runtime | services               | Platform                                                                                                                     | PF-01                     |
| Spring for Apache Kafka                                               | runtime | services, messaging    | Kafka client and listener containers                                                                                         | BOM                       |
| Flyway + PostgreSQL JDBC driver                                       | runtime | services, recon        | Schema migrations, database driver                                                                                           | BOM                       |
| Hazelcast (open-source edition)                                       | runtime | risk-service           | Velocity limits (ADR-0002 #7). **PF-01: confirm the licence of the chosen version is open source and free; record it here.** | PF-01                     |
| Spring Batch                                                          | runtime | reconciliation         | Chunked, restartable end-of-day job                                                                                          | BOM                       |
| Jackson                                                               | runtime | contracts              | JSON messages                                                                                                                | BOM                       |
| JUnit 5, AssertJ, Awaitility, spring-boot-test, spring-security-test  | test    | all                    | Tests                                                                                                                        | BOM                       |
| jqwik                                                                 | test    | payment-service        | State-machine properties                                                                                                     | align with settlement-lab |
| ArchUnit                                                              | test    | all                    | Module and layer rules                                                                                                       | align with settlement-lab |
| Testcontainers (postgresql, kafka)                                    | test    | services, system-tests | Real PostgreSQL and Kafka in tests                                                                                           | BOM                       |

Container images (pinned tags, PF-01): `postgres`, `apache/kafka` (KRaft), `quay.io/keycloak/keycloak`,
`eclipse-temurin` (JRE base for service images).
