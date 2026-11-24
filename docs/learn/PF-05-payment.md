# PF-05: payment-service, the Idempotency-Key and the saga happy path

Code: `payment-service/src/main/java/com/siddheshfulawade/payflow/payment/`. Requirements: REQ-PF-001, 002, 010, 013, 014,
020 and 021 in SPEC.md.
Status of the tests: the local gate without Docker (`mvnw.cmd verify`) is green. The tests that need Docker
(`-Pit`: real PostgreSQL and Kafka, for example `ReplyConcurrencyTest` and `PaymentApiIntegrationTest`) are green in CI
`integration` on `9b29c45`: 360 payment-service tests, coverage gate met. They have not been run locally. 1 test is
skipped on purpose (the TODO-ME).

## What the code does, in plain words

A client sends `POST /api/v1/payments` with an `Idempotency-Key` header and a body (payer, payee, amount, currency)
(`PaymentController.java:38-50`). The service stores the payment and answers 202, or 200 if it is a repeat.
After that, replies from wallet-service and risk-service push the payment along four steps:

```
RECEIVED -> RESERVING -> RISK_CHECKING -> CAPTURING -> COMPLETED
```

`FundsRejected` while RESERVING ends in REJECTED (nothing is held, so nothing to undo). The rules of the table are
pure functions in `SagaStateMachine.java:37-63`: a reply counts only if the payment waits for exactly this reply on
exactly this attempt (`SagaStateMachine.java:38`). Everything else (late, duplicate, other attempt, finished payment)
changes nothing.

Each command is built from the payment row, never from the reply (`SagaStateMachine.java:79-85`), so a forged reply
cannot change who pays whom or how much.

### The API request, step by step (`PaymentService.create`, lines 55-74)

1. Hash the parsed fields (`NewPayment.java:23-33`).
2. Build a RECEIVED payment and `insert ... on conflict (client_id, idempotency_key) do nothing`
   (`JdbcPayments.java:34-59`).
3. If 1 row went in, we own the key. Write the first event, then move RECEIVED to RESERVING and append `ReserveFunds`
   to the outbox, all in the same transaction (`PaymentService.java:72-73`).
4. If 0 rows went in, someone used the key before. Load that payment. Same hash: return it (200). Other hash:
   `IdempotencyKeyReusedException`, shown as 422 (`PaymentService.java:81-88`, `ApiProblems.java:37-43`).

### A reply, step by step (`ReplyHandler.handle`, lines 62-81)

`ReplyListener` reads `wallet.replies` and `risk.replies` and hands the message to the `IdempotentConsumer`
(`ReplyListener.java:45-52`). That opens one database transaction: inbox row, then the handler, then commit. The handler
loads the payment, asks `SagaStateMachine.on`, and `TransitionWriter.write` does three things in this order
(`TransitionWriter.java:37-54`): the guarded update, the audit event, the next command into the outbox.

## Core concepts

### Orchestrated saga

A payment touches two services (wallet, risk) that have their own databases, so there is no single transaction. An
orchestrator (payment-service) keeps the state and tells each service what to do with a command, then waits for a
reply. Compare with choreography, where services react to each other's events and nobody holds the whole picture.
Spring Boot / Kafka analogy: the payment row is the "process state" and the topics are the wires.

### Idempotency key and the request hash

The client may retry after a timeout without knowing if the first request worked. The key makes "create" safe to
repeat. The key alone is not enough: the same key with a different body is a client bug, not a retry. So we store a
SHA-256 of the fields (`payment.request_hash`, `V2__payment.sql:8`). Each field is written as `length:value`, so
characters cannot slide from one field to the next (`NewPayment.java:17-22`, `NewPaymentTest`). The hash is over the
parsed fields, not the raw JSON text, so spaces and key order do not matter. The key is scoped to a client
(`unique (client_id, idempotency_key)`, `V2__payment.sql:25`).

### Optimistic locking and the guarded update

Every payment has a `version`. A transition runs `update payment set ..., version = version + 1 where id = :id and
version = :version` (`JdbcPayments.java:83-86`). If the row changed since we read it, 0 rows match and we throw
`StaleVersionException` (lines 94-96). The whole transaction rolls back: no event, no command, no inbox row, and Kafka
redelivers. On the second try the handler reads the new state and decides again. It is like a JPA `@Version`, written
by hand in SQL. We do not use `select ... for update`: the reads take no lock (`JdbcPayments.java:63`), and the update
itself waits on a busy row and then finds nothing to change, so two transactions can never advance the payment from the
same version.

### READ COMMITTED on the create path

`create` states `Isolation.READ_COMMITTED` (`PaymentService.java:55`). Reason: when two requests with the same key run
at once, the second insert waits for the first. If the first commits, the second insert does nothing, and the next
`select` must see the first one's row. Under READ COMMITTED each statement sees what was committed before it started, so
it does. Under REPEATABLE READ the insert would fail with a serialization error instead. Oracle's default is also read
committed, so this is the behaviour you already know.

## The resend-loop trap (read this one twice)

The `IdempotentConsumer` stores the handler's returned message on the inbox row as the "stored reply". If the same
message id arrives again, it does not run the handler: it re-sends the stored reply. That is right for wallet and risk,
whose reply is the answer.

For payment-service the handler's natural output would be the next command (`CheckRisk` after `FundsReserved`). If we
returned it, it would become the stored reply. Then:

```
duplicate FundsReserved (same message id)
  -> inbox sees it, re-sends the STORED reply = CheckRisk
  -> wallet or risk answers a duplicate command with its own stored reply
  -> that duplicate reply reaches us, is a duplicate message id, re-sends the stored CheckRisk
  -> ... forever
```

So `ReplyHandler.handle` always returns `Optional.empty()` and puts the next command in the outbox itself
(`ReplyHandler.java:50-61, 80`; `TransitionWriter.java:52`). A duplicate reply then changes nothing and sends nothing.
`ReplyHappyPathTest` (line 145) names this rule, and the same test class checks that the handler refuses to run without
the consumer's transaction (line 194), because `Propagation.MANDATORY` keeps the effect and the inbox row together.

## Failure walk-throughs

Written next to the code in the Javadoc of `PaymentService.java:18-30` and `ReplyHandler.java:22-35`. In short:

- **Crash before commit (create)**: payment, events and `ReserveFunds` roll back together. The key is not used up, the
  client's retry creates the payment.
- **Crash after commit, before the HTTP response**: the retry finds the key and gets the original payment (200).
- **Crash after commit, before Kafka publish**: `ReserveFunds` is in the outbox, the relay sends it (maybe twice;
  wallet dedupes).
- **Same key twice at once**: the unique constraint lets one insert win. The other waits, then returns the original
  (or creates it, if the winner rolled back). If the winner hangs, `lock_timeout = 5s` ends the wait with an error and
  nothing written (`application.yml:12`, `PaymentConfigTest.java:67`). Retrying is safe.
- **Same key, other body**: 422, nothing written.
- **Duplicate reply (same message id)**: the inbox drops it.
- **Same reply under a new message id, late reply, wrong attempt, finished payment**: no transition, ignored and logged
  with ids and states only (`ReplyHandler.java:84-93`).
- **Two instances, one payment**: both read the same version, one update wins, the other gets `StaleVersionException`,
  rolls back completely and decides again on the new state when it is redelivered.
- **Lost reply**: the step's deadline passes and the sweeper re-sends the command. The sweeper arrives in PF-06.
- **`RiskRejected` or `CaptureFailed` (money is held)**: not ignored. `SagaStateMachine.needsCompensation`
  (line 69) is true, and the handler throws `IllegalStateException("compensation arrives with PF-06")`
  (`ReplyHandler.java:74-76`). After the 500 ms x 9 retries the record is parked on the dead-letter topic, where a
  human can see it. Until PF-06 the payment stays in its step.

## Text sequence diagram

```
Client      PaymentController   PaymentService   PostgreSQL            Relay -> Kafka        Wallet / Risk
  | POST + key     |                  |               |                     |                     |
  |--------------->| create(...)      |               |                     |                     |
  |                |----------------->| begin tx (READ COMMITTED)           |                     |
  |                |                  |-------------->| insert payment RECEIVED (on conflict nothing)
  |                |                  |-------------->| event (null -> RECEIVED)                  |
  |                |                  |-------------->| update ... version+1 -> RESERVING        |
  |                |                  |-------------->| event + outbox ReserveFunds; commit       |
  |<-- 202 + id ---|<-----------------|               |                     |                     |
  |                                                    |  poll outbox        |                     |
  |                                                    |-------------------->| wallet.commands --->|
  |                                                    |                     |<-- FundsReserved ---| wallet.replies
ReplyListener -> IdempotentConsumer (one tx): inbox row, ReplyHandler.handle
  |                                                    | update (guard) -> RISK_CHECKING; event; outbox CheckRisk; commit
  |                                                    |  relay sends CheckRisk -> risk.commands ->  |
  |                                                    |                     |<-- RiskApproved ----| risk.replies
  |                                                    | update -> CAPTURING; outbox CaptureFunds
  |                                                    |  ... wallet <- CaptureFunds; FundsCaptured back
  |                                                    | update -> COMPLETED (terminal, no deadline); commit
Retry of the POST with the same key and body: insert does nothing, original payment returned, 200.
```

## Other decisions in the code worth knowing

- **Client id is the constant `"local"`** (`LocalClientIdentity.java:6-17`) until PF-07 swaps in the JWT subject. It is
  never taken from a header, because a caller could then read or block another client's keys. Acceptable only while the
  service listens on loopback.
- **Database as last line of defence** (`V2__payment.sql`): `unique (client_id, idempotency_key)`, amount between 1 and
  10,000,000, currency INR, payer different from payee, a deadline only while in flight (lines 25-41). A unique index
  lets a payment enter each state once, and at most one terminal state (lines 67-73).
- **Deadline per step**: `StepDeadlines.in` (`StepDeadlines.java:17-25`) returns a deadline for RESERVING,
  RISK_CHECKING and CAPTURING, none for the others. RELEASING throws until PF-06.

## Five interview questions

**1. Two requests with the same Idempotency-Key arrive at the same moment on two instances. What happens?**
Both run `insert ... on conflict (client_id, idempotency_key) do nothing` (`JdbcPayments.java:34-59`). The unique
constraint lets one win; the other waits on it. If the first commits, the second gets 0 rows, reads the original
payment, compares the hash and returns it as 200 (or 422 if the body differs). If the first rolls back, the second
insert goes ahead and creates the payment. READ COMMITTED is what lets the second `select` see the committed row
(`PaymentService.java:49-55`). If the winner hangs, `lock_timeout` of 5 s ends the wait with an error.

**2. Why a request hash, and why over parsed fields and not the raw body?**
A key reused with another body is a bug, and silently returning the old payment would hide it. Hashing the parsed
fields means whitespace and field order cannot cause a false 422. Each field carries its length, so `ab`+`c` and
`a`+`bc` hash differently (`NewPayment.java:23-26`, `NewPaymentTest`). The hash is stored, so changing the format
would make old retries look like a different body.

**3. Optimistic version or `select for update`? What happens to the loser?**
Optimistic: reads take no lock, and the guarded update (`where id = ? and version = ?`) lets only one transaction move
the payment from a given version (`JdbcPayments.java:83-97`). The loser gets 0 rows, throws `StaleVersionException`,
and the whole transaction (inbox row, event, command) rolls back. Kafka redelivers and the handler decides again on
the new state. Cost: a retry on contention. That is rare, because one payment gets a reply at a time. `for update`
would hold row locks across the whole handler for no gain.

**4. Why does `ReplyHandler.handle` always return empty? What would go wrong otherwise?**
Whatever the handler returns is stored on the inbox row, and a duplicate re-sends it. If it returned the next command,
a duplicate reply would re-send that command, the wallet would answer the duplicate command with its stored reply, that
duplicate reply would re-send the command again, and so on forever (`ReplyHandler.java:54-58`). Returning empty and
appending the command to the outbox in the same transaction (`TransitionWriter.java:52`) means a duplicate does
nothing.

**5. A crash happens between the state update and Kafka. How is the payment not stuck, and not paid twice?**
The state change, the audit event and the next command commit in one transaction; the command goes to the outbox, not
to Kafka (`TransitionWriter.java:37-54`). After a crash the relay finds the unsent row and sends it, possibly twice.
Wallet and risk are idempotent per payment (stored hold and decision), and replies carry the attempt, so a repeat
changes nothing (`SagaStateMachine.java:38`). If nothing comes back, the step's deadline lets the sweeper re-send in
PF-06.

## What breaks if... (exercise)

Suppose someone changes `ReplyHandler.handle` to return `Optional.of(nextCommandEnvelope)` and stops calling
`outbox.append` for it. Work out, with `ReplyHandler.java:62-81` and `TransitionWriter.java:52` open:

1. What does the inbox store, and what does a duplicate `FundsReserved` do?
2. Which message ping-pongs between payment-service and wallet or risk, and why does it never stop?
3. Which test names this rule (hint: `ReplyHappyPathTest.java:145`)? Predict whether it goes red.

Check on a scratch branch with `mvnw.cmd -q -B clean test -pl payment-service -am` (unit tests), then revert. Part 2
needs Kafka and a database (`-Pit`), so reason it through on paper unless CI has run.

## TODO-ME

`payment-service/src/test/java/com/siddheshfulawade/payflow/payment/app/NewPaymentEdgeTest.java`: a disabled, pure unit
test about the request hash. A field that looks like `length:value` must not be able to fake a boundary. What to do and
how to check it are in the comment above the test (marked `// TODO-ME:`). It is `@Disabled`, so the build stays green
while it is unfinished. This is the only TODO-ME of PF-05. The one in `ArchitectureTest.java:78` belongs to PF-01.
