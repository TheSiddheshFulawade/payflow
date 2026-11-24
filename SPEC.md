# PayFlow · v1 spec (crash-safe payment orchestration)

Status: **APPROVED 2026-11-23** by the user (through the chief session). Deadline: **v1.0.0 by 2026-11-30**.
This file is the source of truth: requirements, design and tasks. Change it by PR before changing behaviour.

## 0. Rules that carry over from Settlement Lab

- Original work only. Nothing from any employer: no designs, names, message formats, system names or volumes.
  Generic payments terms only (wallet, payment, hold, risk, statement). **No ISO 20022, no RTGS/NEFT naming.**
- Java 21 language level (`maven.compiler.release` 21), built and tested on JDK 21 and 25. Money is `long` minor
  units, one currency (INR, `currency` field kept for clarity, validated to `INR`).
- Tests first. JaCoCo line coverage ≥ 80 % per service module. Spotless (palantir), ArchUnit, Maven enforcer.
- No new dependency without an entry in `docs/adr/0001-dependencies.md`.
- The user must be able to defend every line: learn notes, README Decisions, one TODO-ME per task, quizzes.

## 1. Problem

A payment that touches several services can fail halfway: a service crashes, the broker restarts, a message is
delivered twice or never answered. Many demo systems lose money or leave payments stuck in those cases.
PayFlow shows a design that **cannot lose money or charge twice, and never leaves a payment stuck**, and proves it
with chaos tests that kill components in the middle of payments.

## 2. Scope (v1)

In: three Spring Boot microservices (payment, wallet, risk), Kafka, PostgreSQL, an orchestrated saga with
compensation, transactional outbox and idempotent consumers in every service, saga timeouts with a recovery sweeper,
Hazelcast velocity limits, OAuth 2.0 resource servers validating Keycloak JWTs, a Spring Batch reconciliation job,
Testcontainers integration and chaos tests, Docker Compose for the whole stack, GitHub Actions CI.

Out (on purpose): API gateway, Redis, notification service, Kubernetes, OpenTelemetry, Prometheus/Grafana,
a UI, cloud deployment, multi-currency, Kafka transactions (EOS), Debezium/CDC, ShedLock.

## 3. Architecture

One repository (monorepo), one Maven multi-module build, **separately deployable services** (one Boot jar and one
container image each). A microservice is defined by independent deployment and its own data, not by its own repo.

```
client ──JWT──► payment-service ──(outbox)──► Kafka ──► wallet-service (payer/payee wallets, holds)
                  │  saga orchestrator        ▲  │
                  │  payment DB                │  └───► risk-service ×2 (Hazelcast cluster, velocity)
                  └──────────◄── replies ──────┘
reconciliation (Spring Batch) ◄── statement CSVs from payment-service and wallet-service
Keycloak issues JWTs · PostgreSQL: one server, one database per service (payment, wallet, risk, recon)
```

| Module            | Kind               | Responsibility                                                                                           |
| ----------------- | ------------------ | -------------------------------------------------------------------------------------------------------- |
| `contracts`       | jar                | Message records (commands, replies), topic names, JSON (de)serialisation, version field                  |
| `messaging`       | jar                | Outbox table + relay (`FOR UPDATE SKIP LOCKED`), inbox (processed messages) + idempotent consumer helper |
| `payment-service` | app :8081          | REST API, `Idempotency-Key`, saga state machine (orchestrator), sweeper, statement export                |
| `wallet-service`  | app :8082          | Wallets, holds, capture, release; command handler; statement export; ops seeding API                     |
| `risk-service`    | app :8083 (+:8093) | Rules: per-payment limit, daily limit, velocity in Hazelcast (2 members)                                 |
| `reconciliation`  | batch app          | End-of-day job: download statements, stage, match, mismatch report                                       |
| `system-tests`    | test-only          | Full stack in Testcontainers; end-to-end and chaos scenarios with invariant checks                       |

Shared libraries (`contracts`, `messaging`) are a conscious trade-off (README Decision): less duplication, at the
cost of coupling the services' release of those two jars. The services never share a database or domain classes.

## 4. The saga (authorise → risk-check → capture)

Modelled on card authorisation and capture: money is **held** first, then checked, then **captured**. No money
leaves the payer until capture, so compensation is always a hold release.

| Step | Command (payment → X)                                    | Success reply   | Failure reply                                         | On failure                  |
| ---- | -------------------------------------------------------- | --------------- | ----------------------------------------------------- | --------------------------- |
| 1    | `ReserveFunds` → wallet                                  | `FundsReserved` | `FundsRejected` (insufficient, wallet frozen/unknown) | → `REJECTED`                |
| 2    | `CheckRisk` → risk                                       | `RiskApproved`  | `RiskRejected` (rule id)                              | `ReleaseFunds` → `REFUNDED` |
| 3    | `CaptureFunds` → wallet (debit payer hold, credit payee) | `FundsCaptured` | `CaptureFailed` (payee frozen/closed)                 | `ReleaseFunds` → `REFUNDED` |

Payment states: `RECEIVED → RESERVING → RISK_CHECKING → CAPTURING → COMPLETED`, plus `RELEASING → REFUNDED`,
and `REJECTED`. **Terminal: COMPLETED, REFUNDED, REJECTED.** `REFUNDED` means the held funds were returned to the
payer's available balance (in card terms, an authorisation reversal).

Wallet money model per wallet: `available` and `held` (both `long`, `CHECK >= 0`). Reserve moves `amount` from
available to held; release moves it back; capture removes it from the payer's held and adds it to the payee's
available, in one local transaction. Invariant: **Σ(available + held) over all wallets never changes.**

Ordering and delivery: every message is keyed by `paymentId` (one partition per payment, so per-payment order).
Delivery is at least once. Every handler is idempotent. The orchestrator ignores replies that don't match the
payment's current step (late or duplicate replies).

Timeouts: each step sets `deadline_at` (step 1 and 3: 10 s; step 2: 5 s; configurable). The **sweeper** (every 2 s)
claims overdue payments with `FOR UPDATE SKIP LOCKED` and re-sends the current command (safe: idempotent) up to
3 times with backoff; after that it compensates (`ReleaseFunds`), and a release is retried until it succeeds.
Release and capture never time out into a terminal state without the wallet's confirmation, so a payment can't end
`REFUNDED` while money is still held.

## 5. Requirements (EARS)

API and security

- REQ-PF-001 When a client POSTs `/api/v1/payments` with a valid body and `Idempotency-Key`, payment-service shall
  store the payment as `RECEIVED` and return 202 with `Location` and the payment id.
- REQ-PF-002 When the same `Idempotency-Key` is sent again by the same client with the same body, payment-service shall
  return the original payment (200) and create nothing; with a different body it shall return 422.
- REQ-PF-003 Every endpoint except `GET /actuator/health` shall require a JWT issued by the configured Keycloak realm;
  `payments:write` to create, `payments:read` to read; a customer may read only payments where they are the payer;
  `ops` role for seeding wallets, statements and reconciliation.
- REQ-PF-004 Invalid input (amount ≤ 0, > 10,000,000 minor units, same payer and payee, unknown fields, wrong
  currency) shall return RFC 9457 problem details, never a stack trace.

Saga

- REQ-PF-010 The orchestrator shall drive each payment through §4 and end it in exactly one terminal state.
- REQ-PF-011 When risk rejects or capture fails, the orchestrator shall release the hold and end `REFUNDED`.
- REQ-PF-012 When a step's reply is overdue, the sweeper shall re-send the command up to 3 times, then compensate.
- REQ-PF-013 Late or duplicate replies shall not change a payment that has moved past that step.
- REQ-PF-014 State changes use optimistic locking (`version`); two instances of payment-service shall never both
  advance the same payment.

Messaging

- REQ-PF-020 Every service shall write outgoing messages to its outbox in the same database transaction as its state
  change; a relay publishes them (`acks=all`, idempotent producer) and marks them sent.
- REQ-PF-021 Every consumer shall record the message id in its inbox in the same transaction as the effect; a
  redelivered message shall have no second effect, and wallet/risk shall re-send the stored reply.
- REQ-PF-022 Consumers commit Kafka offsets only after the database transaction commits.

Wallet and risk

- REQ-PF-030 Wallet balances shall never go negative (database `CHECK` and code check) and Σ(available + held) shall
  never change (except seeding, which is recorded as an `ops` adjustment).
- REQ-PF-031 Risk rules: single payment ≤ 5,000,000 minor units; payer daily total ≤ 20,000,000; velocity ≤ 5
  payments per payer per 60 s. Each rejection names its rule.
- REQ-PF-032 Velocity counts distinct payment ids per payer in a Hazelcast `IMap` updated by an `EntryProcessor`, so a
  redelivered check never counts twice, and both risk-service members see the same count.

Reconciliation

- REQ-PF-040 The reconciliation job shall match the payment statement against the wallet statement for a business
  date and report `MATCHED`, `MISSING_IN_WALLET`, `MISSING_IN_PAYMENTS`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`.
- REQ-PF-041 The job shall be restartable after a crash mid-run without duplicating report rows.
- REQ-PF-042 The README shall publish a measured run: N payments reconciled in T seconds on a named machine.

Chaos and operations

- REQ-PF-050 With services or the broker killed or paused mid-payment (§7 scenarios), every payment shall reach a
  terminal state within 3 minutes after the components return, and the money invariants shall hold.
- REQ-PF-051 `docker compose up` shall start the whole stack; a `demo` script seeds wallets, gets a token and sends
  payments; the README shows the commands.

## 6. Data (Flyway per service)

- payment DB: `payment(id uuid pk, client_id, idempotency_key, request_hash, payer, payee, amount, currency, state,
step_attempts, deadline_at, version, created_at, updated_at, unique(client_id, idempotency_key))`,
  `payment_event` (audit of transitions), `outbox`, `inbox`.
- wallet DB: `wallet(id pk, owner_sub, available, held, status, version, CHECK(available>=0), CHECK(held>=0))`,
  `hold(payment_id pk, payer, amount, state, reason, payee)`, `ledger_entry` (every movement, for statements), `outbox`, `inbox`.
- risk DB: `risk_decision(payment_id pk, payer, amount, decision, rule, decided_at)` (`PENDING` only inside the
  deciding transaction), `daily_total(payer, business_date, amount)`, `outbox`, `inbox`.
- recon DB: Spring Batch job repository, `recon_staging`, `recon_result`.

## 7. Test plan

| Level       | Tool                                                                          | What                                                                                                                                                                                                                       |
| ----------- | ----------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Unit        | JUnit 5, AssertJ                                                              | State machine transitions, rules, validation                                                                                                                                                                               |
| Property    | jqwik                                                                         | Random sequences of replies, duplicates and timeouts never break the state machine (terminal once, no illegal transition)                                                                                                  |
| Integration | Testcontainers PostgreSQL + Kafka                                             | Outbox relay, inbox dedup, each service's handlers, Hazelcast 2-member velocity                                                                                                                                            |
| Security    | spring-security-test + one Keycloak container test                            | Roles, owner check, 401/403                                                                                                                                                                                                |
| System      | Testcontainers (full stack from the Dockerfiles, Keycloak, Kafka, PostgreSQL) | Happy path, risk reject, capture fail                                                                                                                                                                                      |
| Chaos       | system-tests                                                                  | C1 kill wallet-service mid-batch and restart · C2 restart Kafka broker · C3 kill one risk member · C4 pause payment-service past deadlines · C5 client retries every request (same key) · C6 two payment-service instances |

Invariants checked after every system and chaos test: Σ(available + held) unchanged; Σ held = 0; every payment
terminal; each `COMPLETED` has exactly one capture (payer debit + payee credit); each `REFUNDED`/`REJECTED` moved
no money; payments created = distinct idempotency keys; reconciliation of the run reports only `MATCHED`.

CI: GitHub Actions `verify` on Ubuntu and Windows × JDK 21 and 25 (unit tests; the Testcontainers tests are tagged
and run on Ubuntu only) and `system` on Ubuntu (chaos tests, on every PR to main).

## 8. Tasks (feature-sized, test-first, in order)

- [x] PF-01 Build skeleton: Maven wrapper, parent pom (Boot BOM aligned with settlement-lab), all modules empty but
      wired, enforcer, Spotless, JaCoCo, ArchUnit rules, ADR-0001 entries, CI `verify` job, `deploy/compose.yaml` with
      PostgreSQL (4 databases), Kafka (KRaft), Keycloak with the `payflow` realm import
- [x] PF-02 `contracts` and `messaging`: message records with version, outbox + relay with `SKIP LOCKED`, inbox and
      idempotent consumer helper; Testcontainers tests for crash between DB commit and publish, duplicate delivery (REQ-PF-020 to 022)
- [x] PF-03 wallet-service: schema, reserve/release/capture handlers, replies, invariant and concurrency tests,
      ops seeding API (REQ-PF-030, 021)
- [x] PF-04 risk-service: rules, Hazelcast 2-member velocity with `EntryProcessor`, daily totals, idempotent
      decisions (REQ-PF-031, 032)
- [x] PF-05 payment-service API and saga happy path: validation, `Idempotency-Key`, state machine, outbox commands,
      reply handling, optimistic locking (REQ-PF-001, 002, 004, 010, 014)
- [ ] PF-06 Compensation, timeouts and sweeper; jqwik state-machine properties (REQ-PF-011 to 013)
- [ ] PF-07 Security: resource servers in all three services, realm roles, owner check, problem details for 401/403,
      one Keycloak container test (REQ-PF-003)
- [ ] PF-08 Dockerfiles (layered Boot jars, non-root), full `compose.yaml`, `demo` script (PowerShell and sh) (REQ-PF-051)
- [ ] PF-09 system-tests: end-to-end scenarios and chaos C1–C6 with the invariant checker; CI `system` job (REQ-PF-050)
- [ ] PF-10 Reconciliation: statement export endpoints, Spring Batch job (download, stage in chunks of 1,000, match,
      report), restart test, measured run of 100,000 payments (REQ-PF-040 to 042)
- [ ] PF-11 README: architecture and saga diagrams, how to run, Decisions, chaos results table, reconciliation
      numbers; `docs/learn` complete
- [ ] PF-12 Release v1.0.0 (tag) and the hand-back text for the portfolio project card

Cut line if the deadline is at risk, in this order: C6 → the Keycloak container test (mock JWT only) → reconciliation
size 10,000 → Windows CI for `verify`. **Never cut**: outbox/inbox, compensation, sweeper, chaos C1–C3, invariants.

## 9. Resume lines this spec makes true (check each at PF-12)

3 microservices over Kafka with an orchestrated saga and automatic refunds · chaos tests that kill services and the
broker: every payment COMPLETED or REFUNDED, no money lost or charged twice · transactional outbox and idempotent
consumers, saga timeouts and a recovery sweeper · Hazelcast velocity limits with entry processors · OAuth 2.0
resource servers validating Keycloak JWTs with roles · Spring Batch end-of-day reconciliation (add the measured numbers).
