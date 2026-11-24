# ADR-0002 · Architecture: monorepo, orchestrated saga, outbox and inbox

Status: accepted, 2026-11-23

## Decisions

| #   | Decision                                                                 | Alternative                                               | Why                                                                                                                                                                                                                                                                            |
| --- | ------------------------------------------------------------------------ | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | **One repository**, Maven multi-module, one deployable image per service | One repo per service                                      | One owner and one deadline. Atomic changes to a message and its consumers, one CI, shared build rules. Services stay independent at runtime: own process, own database, own image. Many banks use one repo per team instead; the boundary that matters is deployment and data. |
| 2   | **Orchestrated saga** in payment-service                                 | Choreography (each service reacts to events)              | The flow has a strict order and compensation. One place holds the state, so "where is payment X and why" is one query. Choreography spreads the state machine across services and makes timeouts hard to own.                                                                  |
| 3   | **Authorise → risk-check → capture** (hold first)                        | Risk first, then debit, then credit, with a refund credit | No money leaves the payer before capture, so compensation is always a hold release: one simple, idempotent operation. It matches card authorisation and capture.                                                                                                               |
| 4   | **Transactional outbox + polling relay** with `FOR UPDATE SKIP LOCKED`   | Debezium CDC; publish directly to Kafka                   | Writing to Kafka inside a DB transaction can't be atomic (dual write). CDC needs Kafka Connect and Debezium: more moving parts for a 3-service demo. Polling is simple, testable, and scales by adding relays.                                                                 |
| 5   | **Inbox table** for idempotent consumers                                 | Kafka exactly-once transactions                           | Kafka EOS covers only Kafka-to-Kafka. Our effects live in PostgreSQL, so the dedupe must be in the same DB transaction as the effect.                                                                                                                                          |
| 6   | **Timeouts + sweeper** in the orchestrator                               | Rely on Kafka retries only                                | A lost or never-sent reply would leave a payment stuck forever. Deadlines and a sweeper guarantee progress.                                                                                                                                                                    |
| 7   | **Hazelcast** (embedded, 2 members) for velocity                         | Redis; a PostgreSQL counter table                         | Entry processors give atomic per-key updates without locks or Lua. Embedded means no extra server. Distinct payment ids per window make redelivery harmless.                                                                                                                   |
| 8   | **Keycloak + OAuth 2.0 resource servers**                                | Spring Authorization Server; API keys                     | Keycloak is the common enterprise IdP. Each service validates JWTs itself (no gateway in scope).                                                                                                                                                                               |
| 9   | **Statement-based reconciliation** (Spring Batch over CSV statements)    | Reading other services' databases                         | Matches how banks reconcile (two independent statements), keeps service data private, and exercises chunked readers and writers and restartable jobs.                                                                                                                          |
| 10  | Spring JDBC (`JdbcClient`) + Flyway                                      | JPA/Hibernate for everything                              | Locking, `CHECK`s and `SKIP LOCKED` are explicit and easy to explain in an interview.                                                                                                                                                                                          |

## Consequences

- `contracts` and `messaging` are shared jars: a breaking change touches all services in one PR (accepted).
- At-least-once delivery everywhere: every handler must be idempotent, and the tests prove it.
- No cloud deployment in v1: everything runs with Docker Compose, recorded in the README.

## Trust boundary (added at PF-03)

- Kafka is inside the trust boundary in v1: whoever can write to `wallet.commands` can move money. JWTs protect the
  REST APIs only. The demo broker is PLAINTEXT and its host port is published on 127.0.0.1 only.
- A real deployment needs SASL or mTLS and per-topic ACLs: only payment-service writes `*.commands`, only
  wallet-service writes `wallet.replies`, only risk-service writes `risk.replies`, and few people read `*.dlt`.
- Hazelcast is inside the trust boundary too (added at PF-04). The cluster port has no authentication: TLS and member
  authentication are Enterprise features, and the configured member list is for discovery, not an access list. Any
  process that reaches the port and knows the cluster name can join and change velocity counts. The control is the
  network: loopback locally, a members-only network in compose (PF-08), and the port is never published. The port
  accepts members only, not clients, and an allow-list limits what Java deserialization may create. Velocity is a risk
  control: the daily total and the decision stay in PostgreSQL.
- Defence in depth that v1 does have: strict decoding, a command that contradicts the recorded hold is poison
  (dead-letter topic, no effect), guarded SQL and database `CHECK`s.
