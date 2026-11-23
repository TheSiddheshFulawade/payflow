# PayFlow

**Crash-safe payment orchestration.** Kill any service, or the Kafka broker, in the middle of a payment: every
payment still ends `COMPLETED`, `REFUNDED` or `REJECTED`, with no money lost and nothing charged twice.

> Status: in development (v1 due 30 November 2026). The spec is in [SPEC.md](SPEC.md); the decisions are in [docs/adr](docs/adr).

## What it is

Three Spring Boot microservices that talk only through Kafka:

- **payment-service**: REST API with idempotency keys, and the saga orchestrator (authorise → risk-check → capture)
- **wallet-service**: wallets with available and held balances; reserve, capture and release
- **risk-service**: payment and daily limits, plus velocity limits shared by a two-member Hazelcast cluster

The same patterns run through all of them: transactional outbox, idempotent consumers (inbox), saga timeouts with a
recovery sweeper, OAuth 2.0 resource servers validating Keycloak JWTs, and a Spring Batch end-of-day reconciliation.
Chaos tests built on Testcontainers stop, pause and restart components while payments are in flight, then check the
money invariants.

## Run it (after PF-08)

```
docker compose -f deploy/compose.yaml up -d
./deploy/demo.ps1      # seeds wallets, gets a token, sends payments
```

## Decisions

Filled in task by task: what was chosen, the alternative, and why.

## Results

The chaos scenarios table and the reconciliation numbers arrive with PF-09 and PF-10.
