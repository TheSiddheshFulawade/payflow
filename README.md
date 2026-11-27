# PayFlow

**Crash-safe payment orchestration.** Kill any service, or the Kafka broker, in the middle of a payment: every
payment still ends `COMPLETED`, `REVERSED` or `REJECTED`, with no money lost and nothing charged twice.

> Open source (MIT). Status: in development (v1 due 30 November 2026). The spec is in [SPEC.md](SPEC.md); the decisions are in [docs/adr](docs/adr).

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

- **Spring Boot BOM imported, not the parent** — alternative: `spring-boot-starter-parent` — our own parent holds the
  build rules (enforcer, Spotless, JaCoCo, test tags) shared by libraries and services, and Maven allows one parent.
- **Monorepo with shared `contracts` and `messaging` jars** — alternative: a repository per service with copied
  message classes — one change updates every service in one pull request; the cost is coupling through shared jars
  (ADR-0002 #1, SPEC §3 trade-off).
- **JaCoCo excludes `*Application` bootstrap classes** — alternative: a test that calls `main()`, or lowering the
  gate — `main()` only calls `SpringApplication.run`, and the context tests already start each app.
- **Spring Boot 4.1.2** — alternative: 4.2.0, which exists — same version as settlement-lab for alignment across
  projects; upgrading later is one separate change.
- **Hazelcast 5.7.0 Community (Apache 2.0)** — alternative: Enterprise, or the CP Subsystem for strict
  consistency — Enterprise is not open source (the enforcer bans it), and velocity limits need only IMap with an
  EntryProcessor, not CP.
- **One PostgreSQL server, one database and owner per service** — alternative: a server per service — the same
  separation of data (CONNECT revoked from PUBLIC) for a laptop demo with less RAM.
- **Outbox with a polling relay; `sent_at` only after every `acks=all` ack** — alternative: publish to Kafka directly,
  or Debezium change-data-capture — a direct publish cannot commit with the database, so a crash loses or invents
  messages; Debezium adds another moving part. Polling with `SKIP LOCKED` is a few lines, and an early resend is
  harmless because consumers dedupe.
- **Inbox in the effect's transaction** — alternative: Kafka exactly-once transactions — Kafka EOS covers only
  Kafka-to-Kafka, while our effects are PostgreSQL rows; the dedupe must commit with the effect (ADR-0002 #5).
- **Retry 500 ms × 9 then dead-letter topic; poison messages are not retried** — alternative: retry forever — a stuck
  record blocks every payment on its listener thread. The saga sweeper re-sends a parked command and handlers are
  idempotent; a message that cannot be decoded fails the same way every time, so retrying only delays.
- **Closed type registry built from the sealed permits** — alternative: Jackson `@JsonTypeInfo` default typing — the
  JSON must never choose a Java class (deserialization-gadget risk); the registry is complete at compile time and an
  unknown type is poison.
- **`messaging` owns Flyway migration V1 in `db/messaging`; services start at V2** — alternative: copy the DDL into
  each service — one definition of outbox and inbox cannot drift between three services.
- **`messaging` coverage gated under `-Pit` only** — alternative: a unit-only gate — its behaviour lives in Postgres
  and Kafka (`SKIP LOCKED`, `ON CONFLICT`, acks); a unit-only gate would measure mocks, not behaviour.
- **Strict message codec: no coercion, duplicate keys rejected, 64 KiB / depth 16, bounded fields** — alternative:
  Jackson's lenient defaults — the defaults silently turned `100.99` into `100` and let a second `amount` key win
  (found by the PF-02 security review). Money must fail loudly, and a bounded record can never block the outbox relay.
  Error messages name the field, never its value, so logs and the DLT carry no payment data.
- **Hold row = permanent decision, and a per-payment lock** — alternative: a PostgreSQL advisory lock, or the inbox
  alone — the sweeper re-sends with a new message id, so the inbox cannot see the duplicate; the primary key on
  `hold.payment_id` records the decision once and serialises all commands of one payment.
- **Relative guarded updates under ordered row locks** — alternative: read-then-write with a `version` check — the
  database applies `available = available - amount` to the current value, so no update overwrites another and a busy
  wallet never retries; wallets lock in id order, so crossing payments cannot deadlock.
- **Release tombstone** — alternative: ignore a Release with no hold — two `SKIP LOCKED` relays can deliver Release
  before Reserve; without the tombstone the late Reserve would hold money for a payment that already ended REVERSED.
- **Unique `(payment_id, kind)` ledger index** — alternative: trust the code — a payment can never move money twice
  even if a code bug slips past the tests; the database refuses it.
- **Seeding needs no Idempotency-Key; the wallet id is the key (409 on a retry)** — alternative: a key header — the
  id already names the one wallet, so a retried seed changes nothing and says so.
- **16 KiB body limit by Content-Length; chunked bodies refused 411 on every write method** — alternative: buffer and
  count bytes — the container never reads past the declared length, so an endless body cannot use memory. The filter
  runs first and Boot's form filter is off, because that filter reads PUT/PATCH/DELETE form bodies before any limit.
- **A command that contradicts a held or captured hold is a poison message, not a rejection** — alternative: reply
  `FundsRejected(HOLD_MISMATCH)` — a rejection could end the saga REJECTED while the money is still held; the dead-letter
  topic keeps it for a human, with a fixed text and no account values. The hold records the payee on capture so a
  re-sent capture naming another payee is caught too.
- **DB password from the environment, no default** — alternative: a demo default in `application.yml` — a missing
  secret fails at startup instead of silently using a known one; demo values live only in `deploy/`.
- **Velocity call inside the database transaction, bounded at 1 s (was 5 s until PF-06)** — alternative: count after the commit — a payment could then be approved with no
  slot taken. Before the commit, an UNKNOWN count (unreachable, timeout, missing backup ack) throws, rolls back and is
  retried: fail closed. A rolled-back attempt is harmless: the window holds distinct payment ids, so the retry finds
  its own slot. A redelivered check keeps its first slot time, and a member with a fast clock prunes early; both
  accepted. The cost is row locks and a listener thread held while we wait, hence the bound.
- **Permanent decision row with a PENDING claim** — alternative: the inbox alone — the sweeper re-sends with a new
  message id, so the inbox cannot see it; the primary key on `risk_decision.payment_id` decides once, serialises all
  checks of a payment, and a repeat for other money is poison, not an answer.
- **Daily total locked per payer and UTC day, approved amounts only, reversals give no headroom back** — alternative:
  sum over the decisions, or a business time zone — a locked row makes one payer's payments take turns and read a
  committed total; summing would scan and race. UTC is one fixed rule with no daylight saving, and a limit that only
  counts up is simple to explain.
- **Velocity TTL derived from the window (2 x, rounded up)** — alternative: a fixed TTL — a longer window can never get
  a shorter TTL, and an entry outlives its newest payment despite small clock differences between members.
- **Member-only port, exact-name allow-list for Java deserialization, a missing backup ack is an error** —
  alternative: the default unified port (also serves clients), package prefixes, and answering anyway — Hazelcast's
  defaults allow all of `java.*`, so Java deserialization may create only the exact classes listed (no packages, no
  prefixes). Other serializers (`DataSerializable`, zero-config Compact) are not covered by the list. The port has no
  authentication (TLS and member authentication are Enterprise-only) and the member list is discovery, not an access
  list, so the real control is the network: loopback or a members-only network, never published (ADR-0002). A single
  MEMBER endpoint refuses Hazelcast clients. With `fail.on.indeterminate.state=true` a missing backup ack throws, the
  transaction rolls back and the idempotent retry follows; nothing more is claimed.
- **A member starts only when every partition has an owner (30 s bound)** — alternative: Hazelcast's lazy assignment at
  the first use — the first checks then race the partition table to the other member, which drops a backup for a
  partition it does not know yet, without an ack; one drop starts a replica sync that can overtake the next backups, so
  up to 16 of 40 checks waited out their 5 s (measured). The start ends that race for the first checks of a member; it
  does not remove a lost backup ack in general (a member dying or joining): that check throws and its retry is
  idempotent. PF-06 added a wait for a safe cluster, see below.
- **Embedded two members, no split-brain protection** — alternative: a quorum or the CP subsystem — an unknown count
  refuses, but a LOST count approves: during a split, after all members restart (the map is in memory only), or when
  an owner is lost before its backup caught up, a payer may pass more than 5 in a window (up to twice during a split).
  Accepted, because velocity is a risk control, not a money invariant. The daily total and the decision are in
  PostgreSQL and stay exact.
- **RECEIVED and RESERVING in one transaction**. Alternative: commit RECEIVED first and move on in a second step - one
  commit means a crash leaves either nothing (the client retries with the same key) or a payment with its
  `ReserveFunds` already in the outbox. There is no committed payment that nobody is working on.
- **Unique constraint plus a request hash over the parsed fields, at READ COMMITTED**. Alternative: check with a
  `select` first, or hash the raw body - a check-then-insert races; the constraint lets the database pick one winner and
  the loser waits, then reads the winner's row, which needs READ COMMITTED (REPEATABLE READ would fail the insert). A
  hash of the fields (each with its length) ignores spacing and key order. Same key and body returns the original
  (200); same key and another body is 422.
- **Optimistic `version` in a guarded update**. Alternative: `select ... for update` - reads take no lock, and
  `update ... where version = ?` lets one transaction win; the loser rolls back everything and is redelivered, then
  decides on the new state. Contention on one payment is rare, so retries are cheap and no lock is held across the
  handler.
- **Reply handlers return empty and append the next command to the outbox themselves**. Alternative: return the next
  command as the inbox's stored reply - a duplicate reply would re-send the command, the wallet would answer the
  duplicate command with its stored reply, and the two services would loop forever.
- **Client id is the constant `local` until PF-07**. Alternative: an `X-Client-Id` header - a header lets any caller
  read or block another client's keys. The JWT subject replaces the constant in PF-07; until then the service listens
  on loopback only.
- **Compensation replies (`RiskRejected`, `CaptureFailed`) threw until PF-06** (changed by PF-06: they now move the
  payment to RELEASING, see below). Alternative: ignore them - money is held, so ignoring would leave it held with no
  trace. For PF-05 the retries ended in the dead-letter topic, where they were visible.
- **Terminal state `REVERSED`, not `REFUNDED`**. Alternative: `REFUNDED` - in card terms a refund returns captured
  money, but here the hold is released and the payer's money never left, which is an authorisation reversal. The name
  now matches the money movement (`RELEASING`, `ReleaseFunds` and `FundsReleased` keep their names). No behaviour change.
- **Sweeper: ids first without a lock, then one transaction per overdue payment with `for update skip locked`**.
  Alternative: one transaction for the whole batch - one failing payment would roll back the good ones and the locks
  would be held for the whole batch. Deadline and state are checked again under the lock, so a reply or another
  instance that got there first just makes the sweeper skip. Known limit: 50 payments that fail on every sweep could
  fill each batch (logged, accepted).
- **A re-send is the same payload with a new message id and `attempt + 1`**. Alternative: the same message id - the
  receiver's inbox would drop it as a duplicate. The payload is rebuilt from the payment row, so it cannot differ. Wallet
  and risk answer a repeat from their stored decision. Replies to an older attempt are ignored.
- **A release answered `FundsCaptured` ends `COMPLETED`**. Alternative: end `REVERSED` anyway - the payee already has
  the money, so REVERSED would be false. Only `FundsReleased` ends REVERSED, and a release is re-sent forever (with a
  WARN from attempt 4), never given up while money may be held.
- **Re-send a step 3 times (attempts 1 to 4), backoff doubling to a 30 s cap, then release**. Alternative: fixed
  intervals - a service that is down would be flooded. Risk waits 5 + 10 + 20 + 30 = 65 s, reserve and capture 90 s.
  A long outage can therefore end healthy payments REVERSED: money is safe, the client pays again.
- **The sender re-sends instead of Kafka retries (`RetriedBySenderException`)**. Alternative: keep the
  500 ms x 9 retries - with velocity down one command held the instance's listener thread (it serves all partitions)
  for about 55 s against a 5 s deadline. Now the velocity call is bounded to 1 s, the failure is parked after one
  delivery (dead-letter topic), the transaction rolls back the inbox row, and the payment's deadline makes the sweeper
  send the check again. Cost: with one listener thread an instance gets through about one check per second in a
  velocity outage; checks queued behind a few others miss their 5 s deadline and are re-sent, not lost.
- **While RELEASING the wallet's final answer counts for any attempt; every other state needs the exact attempt**.
  Alternative: exact attempt everywhere - a release is re-sent forever with the deadline capped at 30 s, so a wallet
  slower than the cap always answers an older attempt and no answer ever matched (found by the PF-06 audit by reading;
  the first property test was green because it had no latency, and then reproduced it once latency was added). A
  released, rejected or captured hold never changes again, so an old answer is as true as a new one.
- **An overdue payment whose command is still unsent in the outbox is postponed** (same attempt, new deadline, no
  command). Alternative: re-send and count the attempt anyway - a broker outage would spend every attempt, release
  healthy payments and flood the outbox. REQ-PF-012 is read as "a reply is overdue only for a published command".
  Accepted: a command the broker never takes keeps its payment and hold waiting until the fault is repaired, visible
  by a WARN per sweep. Alternative: postpone only up to a maximum age. Known edge: a command published just before its
  deadline gets a short wait, so it may be re-sent early or its payment released early; both are safe.
- **A late `CheckRisk` of an already REVERSED payment is still counted** (daily total, and for 60 s a velocity slot).
  Alternative: look up the payment state in risk - risk has no view of it, and a count that only grows fails closed.
  Accepted.
- **Hazelcast: a pause over 10 s evicts a member, and each side may count alone until the merge**. Alternative: longer
  heartbeat limits - a hung member would then delay checks for longer. Merge timing is to be set in PF-09.
- **`idle_in_transaction_session_timeout = 60 s` in payment-service**. Alternative: rely on TCP keepalive - it can take
  hours to drop a dead session, and meanwhile a frozen instance's relay would hold its outbox rows and the sweeper would
  keep postponing those payments. The relay's normal wait inside its transaction is up to 55 s (10 s in `send()` for
  each of 2 topics, plus 35 s for the acks), so 60 s does not cut off a healthy relay. A relay cut off anyway rolls back
  and sends again; the inbox drops the duplicates. Wallet and risk get the same setting in PF-08.
- **Hazelcast heartbeat 1 s, a hung member evicted after 10 s** (default 60 s). Alternative: the defaults - a check
  would depend on a member that is gone for a minute. 10 s is well inside the 65 s of risk re-sends.
- **A member is handed out once the cluster is safe; not safe in 30 s (one limit with the owner wait): one WARN, and it
  starts anyway**. Alternative: hand it out when every partition has an owner - in CI run 45660859725 one of 40 checks
  that began during the join migrations got no answer and timed out at 1 s (a lost backup ack is inferred; it was not
  reproduced in 100 local runs). Alternative: refuse to start - a check that times out fails closed and is re-sent, a
  missing member answers nothing. A member already running still serves checks while another joins, and one of those
  can still time out.
- **Index in a new migration `V3__overdue_index.sql`**. Alternative: edit `V2` - an applied migration must never change
  (Flyway checksum), and a partial index on `deadline_at` stays small because only in-flight payments have a deadline.
- **Every Hazelcast error is in the deserialization allow-list, as exact class names generated from a jar scan**
  (`hazelcast-allowed-errors.txt`, 124 names: 112 Hazelcast errors and 12 JDK and other base classes up to `Throwable`).
  Alternative: hand-pick the errors a velocity check expects - that missed `PartitionStateVersionMismatchException`, so
  in CI run 45877124629 a join migration's error reply could not be read, the migration was never answered and the
  cluster stayed unsafe for 30 s (likely also the "inferred lost ack" above). Alternative: allow the `com.hazelcast.`
  prefix - that opens every Serializable class in the jar to a crafted stream. The scan test fails on a Hazelcast
  upgrade until the file is regenerated, and a missing or empty file stops the member. Not allowed: arbitrary JDK causes
  and suppressed lists (a `ConnectException` cause is refused, tested). `MemberLeftException.readObject` resolves the
  host the sender wrote, so a crafted stream can force a DNS lookup; accepted, the member port is trust-the-network.

## Results

The chaos scenarios table and the reconciliation numbers arrive with PF-09 and PF-10.

## Licence

Open source under the [MIT licence](LICENSE). Copyright (c) 2026 Siddhesh Fulawade.
