---
name: testing-playbook
description: How PayFlow tests are written - test-first, naming, tags, Testcontainers integration tests, jqwik state-machine properties, system and chaos tests and the invariant checker. Load before writing or reviewing tests.
---

# Testing playbook (PayFlow)

## Rules

- Test first: write the test, see it fail for the right reason, then write the code. A bug fix starts with a test
  that reproduces the bug.
- Name tests after behaviour and the REQ id from SPEC.md, e.g. `REQ-PF-013 late FundsReserved after reversal is ignored`.
- One behaviour per test; arrange, act, assert separated by blank lines. JUnit 5 + AssertJ + Awaitility.
- Tags: none = unit (runs everywhere, including Windows CI); `it` = Testcontainers; `system` = full stack and chaos.

## Testcontainers

- One shared PostgreSQL and one Kafka container per test JVM (singleton pattern), separate databases or schemas per
  test class. Image tags pinned (e.g. `postgres:18.x-alpine`, `apache/kafka:4.x`), listed in ADR-0001.
- No Docker? `it` and `system` tests fail, they don't skip (like settlement-lab). Unit tests don't need Docker.

## State machine properties (jqwik)

Generate random sequences of events for one payment: replies of every type, duplicates, out-of-order and late
replies, timeouts. Properties: reaches at most one terminal state and never leaves it; never captures after a
release; never releases twice; every path from `RESERVING` ends terminal if the wallet eventually answers.

## System and chaos tests (`system-tests`)

- Start the stack from the Dockerfiles on one Docker network: PostgreSQL, Kafka, Keycloak, payment ×1 (×2 for C6),
  wallet, risk ×2. Get a token from Keycloak with the test client.
- Seed 100 wallets, send 1,000 payments from 8 client threads (C5: every request sent twice with the same key).
- Chaos actions: `stop`/`start` a container, `pause`/`unpause`, restart Kafka. Act at a random point while payments
  are in flight.
- Then Awaitility (up to 3 minutes) until every payment is terminal, and run the **invariant checker**:
  Σ(available + held) unchanged, Σ held = 0, each COMPLETED captured once, REVERSED and REJECTED moved no money,
  payments = distinct keys, the reconciliation run is all `MATCHED`.
- Print a one-line result per scenario (payments, COMPLETED, REVERSED, REJECTED, duration). The README chaos
  table is built from these lines.
