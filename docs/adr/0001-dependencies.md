# ADR-0001 · Runtime and test dependencies

Status: living document. Every dependency is listed here before it's added to a pom. Versions are filled in at
PF-01 from the current releases (checked with context7), aligned with settlement-lab where they overlap
(Spring Boot BOM, JUnit, jqwik, ArchUnit, Spotless, JaCoCo).

| Dependency                                                            | Scope   | Module(s)              | Why                                                                                                                          | Version                   |
| --------------------------------------------------------------------- | ------- | ---------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------- |
| Spring Boot (web, validation, actuator, jdbc, oauth2-resource-server) | runtime | services, messaging (jdbc) | Platform. Same version as settlement-lab (4.2.0 exists; we stay aligned). BOM imported in the root pom                      | 4.1.2                     |
| Spring for Apache Kafka                                               | runtime | services, messaging    | Kafka client and listener containers (Boot 4: `spring-boot-starter-kafka`)                                                   | BOM                       |
| Flyway + PostgreSQL JDBC driver                                       | runtime | services, messaging, recon | Schema migrations, database driver (Boot 4: `spring-boot-starter-flyway` + `flyway-database-postgresql`)                 | BOM                       |
| Hazelcast (open-source edition)                                       | runtime | risk-service           | Velocity limits (ADR-0002 #7). **Licence checked 2026-11-23:** `com.hazelcast:hazelcast` 5.7.0 POM declares Apache 2.0 (Community Edition). CP Subsystem is Enterprise-only since 5.5; we don't use it (IMap + EntryProcessor only). Enforcer bans `hazelcast-enterprise` | 5.7.0                     |
| Spring Batch                                                          | runtime | reconciliation         | Chunked, restartable end-of-day job                                                                                          | BOM                       |
| Jackson                                                               | runtime | contracts, system-tests (test) | JSON messages                                                                                                                | BOM                       |
| JUnit (Jupiter 6, from the BOM), AssertJ, Awaitility, spring-boot-test, spring-security-test  | test    | all                    | Tests. `spring-boot-starter-test` also brings Mockito, JSONassert, json-path, Hamcrest and XMLUnit (test scope only, never shipped) | BOM                       |
| jqwik                                                                 | test    | payment-service        | State-machine properties                                                                                                     | 1.10.2 (= settlement-lab) |
| ArchUnit                                                              | test    | all                    | Module and layer rules                                                                                                       | 1.5.1 (= settlement-lab)  |
| Testcontainers (postgresql, kafka)                                    | test    | messaging, services, system-tests | Real PostgreSQL and Kafka in tests (messaging wires them with plain properties)                             | BOM                       |
| spring-boot-testcontainers                                            | test    | services, system-tests | `@ServiceConnection` wires the containers into Spring. Not used by messaging                                                 | BOM                       |

BOM-managed versions come from Spring Boot 4.1.2 (Jackson 3, Spring Kafka, Spring Batch, Flyway, PostgreSQL driver,
JUnit 6.0.3, AssertJ, Awaitility, Testcontainers 2). The enforcer's `dependencyConvergence` rule keeps them consistent.

Build plugins (same versions as settlement-lab): compiler 3.16.0, surefire 3.6.1, enforcer 3.7.0, JaCoCo 0.8.16,
Spotless 3.11.0 with palantir-java-format 2.103.0. Maven 3.10.1 through the wrapper (3.3.4, script-only).

Container images (pinned tags, checked 2026-11-23):

| Image                       | Tag                        | Used by                                         |
| --------------------------- | -------------------------- | ----------------------------------------------- |
| `postgres`                  | `18.7-alpine3.24`          | compose, Testcontainers (= settlement-lab)      |
| `apache/kafka`              | `4.4.0` (KRaft)            | compose, Testcontainers                         |
| `quay.io/keycloak/keycloak` | `26.8.2`                   | compose, the Keycloak container test (PF-07)    |
| `eclipse-temurin`           | `25.0.5.1_2-jre-alpine-3.24` | JRE base for service images (PF-08)           |

CI actions (pinned by commit SHA): `actions/checkout` v7.0.1 `3d3c42e5…`, `actions/setup-java` v6.3.0 `d1374f8a…`.

How versions were checked: context7 wasn't available in the PF-01 session, so the latest releases were read from
Maven Central `maven-metadata.xml`, Docker Hub, quay.io and the actions' git tags, on 2026-11-23.

CI download fallback: `.github/maven-ci-settings.xml` adds Google's mirror of Maven Central
(`maven-central.storage-download.googleapis.com`) as a second repository, used only when Central refuses a hosted
runner. Same artifacts; `-C` makes checksum mismatches fail the build. CI only, never on a developer machine.
