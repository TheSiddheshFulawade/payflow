# PF-09 System tests and chaos tests: does the whole stack keep its promises when parts die?

Status: working tree of branch `feat/pf-09-system`, not committed. Line numbers are from the working tree and may move.
Paths are shortened: `sys/` = `system-tests/src/test/java/com/siddheshfulawade/payflow/system/`.

## What the code does, in plain words

You know unit tests (one class) and Testcontainers tests (one service plus a database). A **system test** starts the
real thing: all containers of `deploy/compose.yaml` (Postgres, Kafka, Keycloak, payment, wallet, two risk members), then
talks to it over HTTP like a client. A **chaos test** is a system test that also breaks something while payments are in
flight (kill a container, restart Kafka, pause a service), puts it back, and then asks one question: is the money still
right and did every payment finish?

Nothing is mocked. The test is the client plus a set of SQL queries.

1. **One stack per JVM.** `SystemStack.ensureUp()` (`sys/SystemStack.java:36`) runs `docker compose up -d --build --wait`
   on the first call and does nothing afterwards. A shutdown hook runs `down -v --remove-orphans` when the JVM ends
   (`:47`, `:114-120`). All tests share it.
2. **Own project name.** Every compose command is built in one place with `-p payflow-systemtest` (`:108-112`,
   constant at `:21`). Compose labels every container, network and volume with its project name, and `down -v` only
   touches things carrying that label. The demo stack runs as project `payflow`, so the test's `down -v` cannot see it,
   let alone remove it. `ComposeProjectIsolationTest` checks this by reading the source (`-p` is present, the name is
   not `payflow`).
3. **The demo-stack guard.** Both stacks publish the same host ports, so they cannot run together. Before `up`,
   `ensureUp` runs `docker compose -p payflow ps -q` (`:44-45`); if that prints any container id,
   `requireNoDemoStack` throws (`:57-62`) with the message to stop the demo first. If the first start fails, `failure`
   is remembered and every later call rethrows it (`:36-39`, `:50-52`). Reason: otherwise the next test would carry on
   and talk to `localhost`, where the demo stack answers.
4. **psql instead of JDBC.** `SystemStack.query` (`:82-106`) runs `docker compose exec -T postgres psql -At -F,` and
   splits the lines on commas. No JDBC driver in the test module, no database port needed. Price: values must contain
   no comma, and ids that go into SQL text pass `SafeIds.require` first (`sys/SafeIds.java:16`), which only lets
   `[a-z0-9-]` through.
5. **A scenario owns its rows.** `Scenario` (`sys/Scenario.java`) gives every wallet an id with a per-run random part
   (`:63-69`), so old rows and demo rows never end up in this test's totals. The client threads retry like a real client:
   connection refused or 5xx means "send again with the same Idempotency-Key" (`:96-128`).
6. **Every test ends the same way** (`Scenario.assertInvariants`, `:223-252`): wait until exactly N payments exist
   and all are terminal (3 minutes, the deadline of REQ-PF-050), take a snapshot from the databases, run
   `InvariantChecker`, and check that payments created equals distinct keys sent.

### The chaos scenarios (`sys/ChaosSystemTest.java`)

Each sends 40 payments: 5 payers with a lot of money send three small payments (COMPLETED) and one over the risk limit
(REVERSED); 5 poor payers send four payments above their balance (REJECTED). So the right answer is 15 / 5 / 20, and
only a fault could change it. The fault is injected when 20 payments have been accepted (`:159-163`).

| Scenario | Fault | What makes it safe |
| -------- | ----- | ------------------ |
| C1 (`:41`) | `kill wallet-service`, wait 5 s, `start` | Payment-service wrote its command to its **outbox** in the same transaction as the state change, so the command survives; the relay sends it when Kafka and the wallet are reachable. The wallet's **inbox** drops a command it already handled, so a re-send cannot reserve twice. |
| C2 (`:59`) | `restart kafka` | Same pair: the outbox keeps the message until Kafka confirms, the relay retries; consumers see duplicates and the inbox absorbs them (at-least-once delivery plus idempotent consumers). |
| C3 (`:72`) | `kill risk-1`, never restarted before the check; also 7 payments from one payer | **Hazelcast backups**: the velocity counters live in a map with `setBackupCount(1)` (`risk-service/.../HazelcastConfigFactory.java:95`), so risk-2 holds a copy and the limit of 5 per minute still holds. Expected: 5 of the 7 pass (COMPLETED 15+5), 2 are REVERSED (5+2). |
| C4 (`:99`) | `pause payment-service` 45 s, `unpause` | While it is frozen no reply is processed and every step deadline passes (the longest is 30 s). When it wakes, the **sweeper** (`payment-service/.../app/PaymentSweeper.java`) finds payments past their deadline and re-sends the command or starts the release; the other side's inbox absorbs the repeat. |
| C5 (`:120`) | every request sent 3 times at once with one key, plus a wallet kill | The **unique constraint** `(client_id, idempotency_key)` (`payment-service/.../V2__payment.sql:25`) lets only one insert win; the others get the original payment back. 120 requests must give exactly 40 payments, and the checker's `DISTINCT_KEYS` proves it. |

**C6 is cut** (two payment-service instances): SPEC section 8 puts it first on the cut line (`SPEC.md:205`, "C6 -> the
Keycloak container test -> reconciliation"). It needs a second payment-service in compose and a shared-sweeper argument
the other scenarios do not need. It stays listed in the SPEC table (`SPEC.md:172`) as not done; do not claim it.

### The invariant checker (`sys/invariants/InvariantChecker.java`)

Pure Java over a `Snapshot`, so it has its own fast unit tests (`InvariantCheckerTest`) with no Docker. It reports every
broken invariant, not only the first (`:30-41`), and sums with `Math.addExact` so an overflow fails instead of wrapping.

| Invariant | Meaning | Line |
| --------- | ------- | ---- |
| `SUM_UNCHANGED` | sum(available + held) over the test wallets equals the total seeded: no money created or lost | `:45` |
| `NOTHING_HELD` | sum(held) = 0: every hold was captured or released | `:55` |
| `ALL_TERMINAL` | every payment is COMPLETED, REVERSED or REJECTED | `:61` |
| `ONE_CAPTURE` | a COMPLETED payment has exactly one `CAPTURE_DEBIT` on the payer and one `CAPTURE_CREDIT` on the payee, both for the amount | `:70` |
| `NO_MONEY_MOVED` | a REVERSED or REJECTED payment has no capture and reserve minus release is 0 | `:87` |
| `DISTINCT_KEYS` | payments per (client, key) is at most 1 | `:105` |

### What is not checked in chaos runs, and what covers it

SPEC section 7 has a last invariant, "reconciliation of the run reports only MATCHED". Chaos runs do **not** check it
(`ChaosSystemTest.java:21-23`, `Scenario.java:220-221`, `InvariantChecker.java:17`). The reason is the closed-date
rule: the reconciliation job refuses any business date later than today minus 2 (UTC) because yesterday may still be
settling (`recon/app/ReconciliationRun.java:108-115`, see the PF-10 note). A payment made in a test run is dated
today, so the job cannot look at it. Faking the date would test the fake. What covers the gap instead:
`docs/results/PF-10-measured-run.md`, where 10,000 and 100,000 rows were put into the databases on a closed date and
the real job produced all MATCHED. That proves the job on clean data; it does not prove the chaos runs reconcile. Say
that plainly in an interview.

### Measured results (one run each, not a benchmark)

2026-11-28, Docker Desktop 28.1.1, Ryzen 7 5800H, log `system-tests/target/psystem-run1.log`:

| Scenario | Payments | COMPLETED / REVERSED / REJECTED | Time |
| -------- | -------- | ------------------------------- | ---- |
| C1 | 40 | 15 / 5 / 20 | 49 s |
| C2 | 40 | 15 / 5 / 20 | 11 s |
| C3 | 47 | 20 / 7 / 20 | 50 s |
| C4 | 40 | 15 / 5 / 20 | 51 s |
| C5 | 40 (120 requests) | 15 / 5 / 20 | 116 s |

The three end-to-end tests ended COMPLETED, REVERSED and REJECTED as expected, all invariants held, 94 tests, 0
failures. One run each: this shows the design works on this machine once, not how often it could fail. A flaky
timing window would need many repeats to show.

## Core concept: at-least-once plus idempotent, then check the result, not the path

Kafka may deliver a message twice, a service may die between two lines, a client may retry. You cannot stop those. What
you can do is make every step safe to repeat (outbox: do not lose it; inbox and unique key: do not apply it twice;
sweeper: do not wait forever) and then test the **outcome** with rules that must hold whatever path the messages took.
Chaos tests do not predict the path; they assert the invariants.

## Sequence: C1, wallet-service killed mid-batch

```
Client      payment-service      Kafka        wallet-service        Postgres
  | POST x20 --> |                  |               |                   |
  |              | state+outbox row (1 tx) ---------------------------> |
  |              | relay -> ReserveFunds -> |        |                   |
  |              |                  | ----> handle, inbox row, hold ---> |
  |   ... 20 accepted: test runs `docker compose kill wallet-service`   |
  | POST x20 --> | outbox rows wait; relay sends; nobody consumes yet   |
  |              |                  |  (commands pile up, deadlines run)|
  |   5 s later: `start wallet-service`                                 |
  |              |                  | ----> handle (inbox drops repeats) |
  |              | <---- FundsReserved ----- | reply via wallet outbox     |
  |              | sweeper re-sends where a deadline passed (harmless)  |
  |  poll SQL: 40 payments, all terminal (<= 3 min) -> InvariantChecker  |
```

## Interview questions

1. **What is the difference between a system test and an integration test, and why do you need both?**
   An integration test (our `-Pit` Testcontainers tests) starts one service with the infrastructure it needs and calls
   its code directly; it is fast and pinpoints a bug. A system test starts every real container, including the
   Dockerfiles, the compose wiring, the issuer and key URLs and Kafka between the services, and acts as an HTTP client.
   It finds what no single service test can: a wrong env var, a missing network, a message format two services read
   differently. It is slower and says less about where the bug is, so you keep few of them.

2. **How do you make a chaos test that is not flaky?**
   Assert invariants that must hold on every path, not a particular order of events. Make the input deterministic (here
   15/5/20), inject the fault at a counted point (20 accepted), always undo it in a `finally`, and wait with Awaitility
   for the end state with a stated deadline, never a bare sleep. The client retries like a real one. Then a slow run
   passes and a wrong state fails. Honestly: we ran each once, so a rare timing bug could still hide.

3. **Why can the test's `down -v` never remove the developer's demo stack?**
   Compose scopes everything by project name. Every command goes through one method that adds `-p payflow-systemtest`,
   while the demo runs as `payflow` (`SystemStack.java:21`, `:108-112`). `down -v` removes only that project's
   containers, networks and volumes. A second guard checks that the demo project has no containers before `up`,
   because both use the same host ports; if it does, the test stops with a clear message instead of talking to the wrong
   stack (`:44-45`, `:57-62`).

4. **Name the six invariants and say which one catches a double charge.**
   Sum unchanged, nothing held, all terminal, one capture per COMPLETED payment, no money moved for REVERSED/REJECTED,
   distinct keys. A double charge shows as `ONE_CAPTURE` (a COMPLETED payment with four capture entries instead of two)
   and usually as `SUM_UNCHANGED` too. A duplicate payment from a retry shows as `DISTINCT_KEYS`. Because the checker
   returns all violations, one run shows both.

5. **Why is "reconciliation reports only MATCHED" not part of the chaos runs, and are you sure it would pass?**
   The job only reconciles dates up to today minus 2 so it can ignore yesterday's still-settling lines, and a test run
   creates payments dated today; making it look at them would mean faking the clock or the data. So it is covered by the
   PF-10 measured run on seeded rows of a closed date (10,000 and 100,000, all MATCHED, one run each). I am not sure
   chaos-day data would reconcile: that is an open gap I would close with a test clock, not claim as done.

## What breaks if...

Exercise: in `SystemStack.compose` (`:108-112`), someone changes `PROJECT` to `"payflow"` "to reuse the demo images".
Work out, step by step: (a) what does the guard at `:44-45` do when the demo stack is up, and what does it miss when the
demo stack is down at start but started by the developer mid-run? (b) At JVM exit, what does `down -v` now remove, and
what happens to the developer's demo data? (c) Which existing test fails first, and which line of it? (Hint:
`ComposeProjectIsolationTest.java:28-31`.) Then explain why the guard alone is not enough protection.

## TODO-ME

`sys/SafeIdsTest.java`, test `emptyListHasNoSafeSqlForm` (disabled so the build stays green). `SafeIds.sqlList(List.of())`
returns an empty string, so `... where payer in ()` is invalid SQL and the harness fails with a confusing psql error
instead of a clear message. Decide the behaviour (refuse with `IllegalArgumentException` is the simplest), write the
assertion, remove `@Disabled`, see it fail, then make `SafeIds.sqlList` satisfy it. Check:
`./mvnw.cmd -q -B test -pl system-tests -Dtest=SafeIdsTest`.
