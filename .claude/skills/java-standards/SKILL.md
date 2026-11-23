---
name: java-standards
description: Java and Spring Boot conventions for PayFlow - modules, packages, money, validation, errors, persistence, security and the build gate. Load before writing or reviewing Java code.
---

# Java standards (PayFlow)

SPEC.md is the source of truth. This skill is the short checklist.

## Modules and packages

- Base package `com.siddheshfulawade.payflow`, then the module: `.contracts`, `.messaging`, `.payment`, `.wallet`,
  `.risk`, `.recon`. Inside a service: `api` (controllers, request/response records), `domain` (state, rules,
  no Spring), `app` (use cases, transactions), `infra` (JDBC repositories, Kafka adapters, config).
- ArchUnit rules: `domain` imports no Spring and no `infra`; services never import another service's packages;
  only `contracts` types cross service boundaries.

## Style

- Records for values and messages, sealed interfaces for outcomes, small classes, clear names. No Lombok.
- Money is `long` minor units; currency is validated to `INR`. Never `double`.
- `release 21`: no API newer than Java 21. Constructor injection only. `Optional` only as a return type.
- Persistence: Spring JDBC (`JdbcClient`) with Flyway migrations per service. No JPA entities for money tables:
  explicit SQL is easier to defend (row locks, `CHECK`s, `SKIP LOCKED`). Spring Data JPA may be used for the
  read-only `payment_event` audit if it keeps code simpler; record that in README Decisions.
- Transactions: `@Transactional` on application services only, never on controllers. Keep them short, with no
  network calls inside.

## API

- Jakarta Validation on request records; reject unknown JSON fields; 16 KiB body limit.
- Errors are RFC 9457 `ProblemDetail` with a stable `type` URI, never stack traces or library messages.
- `Idempotency-Key` header required on POST (1 to 64 chars `[A-Za-z0-9-_]`).
- Security: `spring-boot-starter-oauth2-resource-server`, issuer from config, roles from the realm `roles` claim
  mapped to authorities, owner checks in the application layer. Only `health` is public on actuator.

## Gate

`mvnw.cmd verify` (Windows) or `./mvnw verify`: compile, unit tests, JaCoCo ≥ 80 %, Spotless, ArchUnit, enforcer.
`-Pit` adds Testcontainers integration tests, `-Psystem` the full-stack chaos tests. `mvnw.cmd spotless:apply`
fixes formatting.
