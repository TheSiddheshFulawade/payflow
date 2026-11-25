# PF-06: compensation, timeouts and the recovery sweeper

Code: `payment-service/src/main/java/com/siddheshfulawade/payflow/payment/` (and `risk-service`, `messaging` for the
bounded velocity wait). Requirements: REQ-PF-010 to 014 and 020 in SPEC.md.
Status of the tests: CI (PR #7) runs the `-Pit` tests (real PostgreSQL and Kafka); this note does not claim their
results. 1 test is skipped on purpose (the TODO-ME).

## What the code does, in plain words

PF-05 moved a payment forward when replies came back. PF-06 handles the other cases: a service says no after money is
held, a reply never comes, or a message is parked on the dead-letter topic. Three pieces:

1. **Compensation.** `RiskRejected` (while RISK_CHECKING) and `CaptureFailed` (while CAPTURING) move the payment to
   RELEASING and queue `ReleaseFunds` (`SagaStateMachine.java:69, 78, 115-117`). `FundsReleased` then ends it REVERSED
   (lines 80-81).
2. **Deadlines with backoff.** Every waiting state has a deadline that doubles with each attempt up to a cap
   (`StepDeadlines.java:30-48`). Settings are bounded: each at most 10 minutes and not above the cap, so a typo cannot
   park money for hours (constructor, lines 18-28).
3. **The sweeper.** Every 2 s it finds payments past their deadline and either re-sends the command, starts the
   release, or postpones (see below) (`PaymentSweeper.java:100-145`, `SweeperSchedule.java:31`).

```
RECEIVED -> RESERVING -> RISK_CHECKING -> CAPTURING -> COMPLETED
                |              |              |
             REJECTED      RiskRejected / CaptureFailed / no reply after the re-sends
                               \________________|
                                        v
                                    RELEASING -> REVERSED   (or COMPLETED, see below)
```

### Attempts and deadlines

`step_attempts` counts sends of the current step's command. A new step starts at attempt 1. Defaults:
reserve 10 s, risk 5 s, capture 10 s, release 10 s, cap 30 s (`DeadlinesProperties.java:10-15`); sweeper every 2 s,
50 payments per sweep, 3 re-sends (`SweeperProperties.java:17-21`).

With 3 re-sends a step is sent as attempts 1, 2, 3 and 4. When attempt 4 is overdue, `SagaStateMachine.overdue`
returns `Compensate` (`SagaStateMachine.java:93-99`: re-send while `stepAttempts() <= maxResends`). Deadline for
attempt n is `min(base x 2^(n-1), cap)`. So risk waits 5 + 10 + 20 + 30 = 65 s in total, reserve and capture
10 + 20 + 30 + 30 = 90 s. `StepDeadlinesTest` has the table, including an attempt number of `Integer.MAX_VALUE`
(the loop stops at the cap, `StepDeadlines.java:42-48`, so there is no overflow).

A re-send keeps the payload, because the command is rebuilt from the payment row (`SagaStateMachine.java:102-112`). It
gets a new message id and `attempt + 1` (`TransitionWriter.java:64-70`). The new id matters: the receiver's inbox would
drop the old one as a duplicate. It writes no event row, because a payment enters each state once (unique index).

### The sweeper, step by step

1. `overdueIds`: `select id ... where deadline_at < :now order by deadline_at limit :limit`, no lock
   (`JdbcPayments.java:116-122`). The partial index from `V3__overdue_index.sql` keeps this cheap, because only
   in-flight payments have a deadline.
2. For each id, one transaction of its own (`PaymentSweeper.java:129-132`). Inside it `lockOverdue` selects the row
   again with `for update skip locked`, and checks the deadline and the state again (`JdbcPayments.java:125-136`).
3. **Postpone first** (`PaymentSweeper.java:134-139`, `JdbcPayments.java:139-145`): if the payment's command is still
   unsent in the outbox (`sent_at is null`), nothing is late, because there was never a send to wait for. The sweeper
   keeps the same attempt, sets a new deadline, sends no command and writes no event (`TransitionWriter.java:78-82`). An
   outage therefore costs no attempts and does not pile up commands behind a stuck one. V3 has a partial index on
   unsent `message_key` for this question.
4. Otherwise `SagaStateMachine.overdue` decides, `TransitionWriter` writes the row, and the command goes to the outbox.
   All in the same transaction.
5. If one payment throws, only its transaction rolls back. The loop logs the exception types and goes on
   (`PaymentSweeper.java:116-118`, `logFailed` 160-167). Postponed payments give one WARN line per sweep, not one per
   payment (lines 120-123).

Consequence accepted (README Decisions): REQ-PF-012 is read as "a reply is overdue only for a published command". A
command the broker never takes (for example a missing topic) keeps its payment, and its hold, waiting until someone
repairs the fault. The WARN makes it visible. An alternative would postpone only up to a maximum age. Known edge: a
command published just before its deadline gets only a short wait, so it may be re-sent early, or on the last attempt
released early. Both are safe.

Spring Boot / Oracle analogy: `skip locked` is the same idea as Oracle's `SELECT ... FOR UPDATE SKIP LOCKED` that you
may know from queue tables. A second instance simply passes over rows that the first is working on.

### Sweeper against a reply (the race)

A late reply and the sweeper may touch the same payment in the same millisecond. The row lock and the guarded update
(`where id = ? and version = ?`, `JdbcPayments.java:84-94`) decide:

- Reply commits first: the payment moved, so under the lock the sweeper no longer finds it overdue and skips it.
- Sweeper commits first: the reply's update matches 0 rows, throws `StaleVersionException`, rolls back (inbox row too)
  and is redelivered and decided again. Usually the payment is then on attempt + 1 and the reply, carrying the old
  attempt, is ignored. Two exceptions: the sweeper only postponed (same attempt, so the reply counts), or the reply
  ends a release (see the next section).

Nothing is lost: the re-sent command gets its own answer, from the same stored decision in wallet or risk.

### Late and old-attempt replies

Outside RELEASING a reply counts only when its attempt equals the row's `stepAttempts` and the payment waits for it
(`SagaStateMachine.java:46-53`). A terminal payment accepts nothing. So the answer to attempt 1 that arrives after the
re-send is ignored and logged (`ReplyHandler.java:83-92`).

**While RELEASING the attempt is not checked** (`SagaStateMachine.java:41-44, 51`). Both answers that end a release
state a final fact of the hold: a hold that is released, rejected or captured never changes again, so an old answer is
as true as a new one. Why this was needed: a release is re-sent forever with the deadline capped at 30 s. A wallet that
answers more slowly than that always answers an older attempt, so with an exact attempt check no answer would ever
match and the payment would never end. Everything else (RiskRejected, FundsReserved and the rest) still needs the exact
attempt.

`FundsCaptured` is accepted in CAPTURING and in RELEASING and ends COMPLETED (`SagaStateMachine.java:70-76`). Reason:
when the wallet gets `ReleaseFunds` for a hold that was already captured, it answers `FundsCaptured`, the truth. The
payee has the money, so the payment is COMPLETED, never REVERSED. REVERSED means an authorisation reversal: money was
held and released, and it never left the payer. A late reply to the capture itself may match too. It says the same
thing.

A release is never given up (`SagaStateMachine.java:95`). Giving up would end the payment, or strand it, with money
still held. It is re-sent at the capped interval and logs a WARN from attempt 4 (`PaymentSweeper.java:65, 149-153`).

## Core concepts

### Compensation instead of rollback

There is no distributed transaction, so an undo is a new forward step: release the hold. `release` is safe even if
nothing is held, because the wallet answers and leaves a tombstone that refuses a Reserve arriving later
(`SagaStateMachine.java:114-117`; README, PF-03 note).

### One transaction per payment

Alternative: one transaction for the whole batch. Then one bad payment would roll back 49 good ones, and row locks
would be held for the whole batch. With one per payment a failure is contained, and the isolation level is stated
(READ COMMITTED, `PaymentSweeper.java:86-88`) because `skip locked` re-checks a row that changed in the newest version.

### The 55 s block versus the 5 s deadline

Before PF-06: if velocity was down, a risk command waited 5 s per try, with 10 tries and 500 ms pauses. About
10 x 5 + 9 x 0.5 = 55 s (PF-04 note) while the risk step's deadline is 5 s. The sweeper would queue its re-sends behind
the blocked record. Note what is blocked: one listener thread serves all partitions of an instance, so the block hits
every payment on that instance, not just one partition.

Resolution, in three parts:

- The velocity call is bounded to 1 s (`HazelcastVelocityCounter.java:30`).
- A velocity failure throws `VelocityUnavailableException`, a `RetriedBySenderException`. The error handler does not
  retry it: one delivery, then the dead-letter topic (`MessagingAutoConfiguration.java:140`). So the listener thread is
  held for 1 s at most per check.
- The sender re-sends. The payment's deadline passes and the sweeper sends the check again with a new id
  (`RiskCommandHandler.java:50-52`). The rollback also removed the inbox row, so the parked record cannot be mistaken
  for a duplicate (`RetriedBySenderException.java:10-11`).

Limit, stated plainly: with one listener thread, an instance gets through about one check per second during a velocity
outage. A check that queues behind more than a few others misses its 5 s deadline and is re-sent. It is not lost.

A hung (not dead) member is evicted after 10 s without a heartbeat, with a heartbeat every 1 s
(`HazelcastConfigFactory.java:39-42`), and its backups take over. That is well inside the 65 s of risk re-sends. A
pause longer than 10 s (for example a long garbage collection) evicts a member too. Then each side may count on its own
until the cluster merges, so a payer can briefly pass more than the limit. How long a merge takes is to be set in PF-09.

### A frozen instance must not hold outbox rows

The relay claims outbox rows with `for update skip locked` inside one transaction and keeps it open while it waits for
Kafka (`OutboxRelay.java:56-61, 80-84, 93-96`). Its normal wait is up to 55 s: 10 s in `send()` for each of the 2 command
topics, plus 35 s for the acks. If the instance freezes (a long pause, a lost network) the session stays open and the
rows stay locked. The sweeper then sees those commands as unsent and postpones their payments, until PostgreSQL drops
the dead session, which can take hours with TCP keepalive alone.

So payment-service sets `idle_in_transaction_session_timeout = 60 s` for every connection (`application.yml:11-15`).
60 s is above the relay's normal 55 s, so a healthy relay is not cut off. A relay that is cut off anyway rolls back and
sends its batch again; the receivers' inboxes drop the duplicates. Wallet and risk get the same setting in PF-08.

## The property test

`SagaModelPropertyTest` runs the real `SagaStateMachine` against a small model, 1500 random scenarios.

- **Fake wallet**: one hold per payment (NONE, RESERVED, REJECTED, RELEASED, CAPTURED). A reserve is a permanent
  decision. After a release a Reserve is refused (tombstone). A release of a captured hold answers `FundsCaptured`. A
  refused capture leaves the hold RESERVED, and the outcome of the n-th capture may change between attempts
  (`SagaModelPropertyTest.java:467-510`).
- **Fake risk**: a permanent decision, approve or reject (`serve`, line 457).
- **Fake network**: delivers, loses (into "limbo"), duplicates and reorders commands and replies, and a lost message
  can turn up again in the middle of a later step. The payment side may crash before its commit, publish late (outbox
  lag) and time out at any moment (`step`, lines 217-247).
- **Latency (0 to 4)**: with latency 0 the fair phase answers within one timeout. With latency 1 to 4 every round trip
  spans that many timeouts, so every answer arrives for an older attempt (`slowFairPhase`, line 290).
- **Checked after every step**: one terminal state and never left; attempts grow by one inside a state; at most one
  capture; COMPLETED if and only if the wallet captured; REVERSED or REJECTED never while money is held or captured;
  replies accepted only for the current attempt, except the wallet's final word while RELEASING; every command equals
  the one built from the row; no CaptureFunds after a ReleaseFunds (`check`, line 514; `replyToPayment`, 338-360).
- **Liveness**: after a fair phase the payment is terminal within 40 rounds, and then every message still in flight
  arrives late and changes nothing (`run`, line 200-202; `lateDeliveryOfEverythingLeft`, line 311).
- **Rare paths** are counted, and the run fails if one is never reached (`sampledTheRarePaths`, line 141), so a change
  to the generator cannot quietly stop testing them.

What happened, honestly:

1. The first version of this test was green although the implementation had a bug (M1 in the audit): while RELEASING,
   a reply was accepted only for the exact current attempt. A release is re-sent forever with the deadline capped at
   30 s, so a wallet slower than the cap always answered an older attempt, and no answer ever matched.
2. The test was green because it had no latency: every answer came back within one timeout. The test did not find the
   bug. The audit found it by reading the code.
3. Latency was then added to the test. It reproduced the bug ("not terminal after 40 fair rounds, latency 2", line 202).
   The fix (the attempt is not checked while RELEASING, `SagaStateMachine.java:51`) made it green.
4. The teeth, shown by breaking the code on purpose: putting attempt equality back is caught as "not terminal" (2
   tries); accepting `FundsCaptured` in RELEASING as REVERSED is caught as REVERSED while the money was captured (5
   tries).

The lesson for an interview: a property test is only as good as the world it models. This one missed a bug until the
model got a slow wallet.

## Text sequence diagram

```
Sweeper(2 s)       PostgreSQL                         Outbox/Relay -> Kafka       Wallet
  | overdueIds(now, 50) -> ids (no lock)
  | for each id: begin tx (READ COMMITTED)
  |----------------->| select ... where deadline_at < now for update skip locked
  |                  |   (0 rows: moved or locked by another: skip)
  |----------------->| unsent command in the outbox? yes: same attempt, new deadline, no command; commit (postponed)
  | overdue(): attempt <= 3 ? Resend : Compensate
  |----------------->| update state/attempt+1/deadline (version guard)
  |----------------->| outbox: same command, new message id, attempt+1; commit
  |                                                    |-- CaptureFunds(attempt 2) -->|
  |                                                    |<-- FundsCaptured(attempt 2) -|
ReplyHandler: attempt 2 == row attempt 2, CAPTURING -> COMPLETED

Compensation:  attempt 4 overdue  -> RELEASING (attempt 1), outbox ReleaseFunds
  wallet: hold RESERVED -> RELEASED, FundsReleased -> REVERSED
  wallet: hold CAPTURED -> answers FundsCaptured -> COMPLETED (never REVERSED)
  an answer to ANY earlier release attempt ends it too (only while RELEASING)
```

## Limits (said honestly)

- An outage of Kafka no longer spends attempts: a payment whose command is unsent is postponed. But a service that is
  down while commands are published, or a slow one, can still use up the re-sends (65 to 90 s), and then healthy
  payments end REVERSED. Money is safe (REVERSED only after the wallet released), but the client must pay again.
- A command the broker never takes keeps its payment and its hold waiting until the fault is repaired. Only a WARN shows
  it (see the sweeper section).
- Starvation: `overdueIds` orders by deadline and takes 50. A payment that fails on every sweep keeps the oldest deadline
  and so keeps its place. 50 such payments at once would fill every batch and hold up the rest. Logged on every sweep;
  accepted for now.
- A late `CheckRisk` of a payment that is already REVERSED is still counted: it adds to the payer's daily total and, for
  60 s, takes a velocity slot. It fails closed (it can only make limits stricter). Accepted.
- No alert metric yet, only a WARN log for releases past attempt 3 and for postponed payments.

## Five interview questions

**1. A reply is lost. What gets the payment moving again, and why is it safe to send the command twice?**
The step's `deadline_at` passes and the sweeper re-sends the same payload with a new message id and attempt + 1
(`TransitionWriter.java:64-70`). Wallet and risk are idempotent per payment (a hold row, a decision row), so a command
that already ran is answered again, not run again. A new message id is needed, because the receiver's inbox would drop
the old id.

**2. Why `for update skip locked` and one transaction per payment?**
Two sweepers (two instances) must not work on the same payment: `skip locked` makes the second pass over a locked row
instead of waiting (`JdbcPayments.java:125-136`). One transaction per payment means a failure rolls back only that
payment, and row locks are held briefly (`PaymentSweeper.java:129-132`). Ids are read without a lock first, so the
state and deadline are checked again under the lock.

**3. The sweeper and a reply hit the same payment at the same time. Walk through it.**
Both rely on the row lock and the `version` guard (`JdbcPayments.java:84-94`). If the reply commits first, the payment
is no longer overdue and the sweeper skips it. If the sweeper commits first, the reply gets 0 rows, throws
`StaleVersionException`, rolls back with its inbox row, and on redelivery it is decided again. Outside RELEASING an
old attempt is ignored (`SagaStateMachine.java:51`); if the sweeper only postponed, the attempt is still current and the
reply counts; while RELEASING the wallet's final word counts for any attempt. Nobody advances the payment twice.

**4. A release is answered `FundsCaptured`. Why is the payment COMPLETED, not REVERSED? And why does a release answer
count for any attempt?**
Because the wallet had already captured: the payee has the money (`SagaStateMachine.java:70-76`). Ending REVERSED
would tell the client the money returned when it did not. REVERSED is reached only on `FundsReleased`
(lines 80-81). A release is re-sent forever with a capped deadline, so a slow wallet's answer always belongs to an
older attempt. The hold's final facts do not change, so the old answer is as true as a new one (lines 41-44, 51).

**5. A velocity outage used to block a listener thread for about 55 s. How was that fixed, and what did it cost?**
The call is bounded to 1 s (`HazelcastVelocityCounter.java:30`), the failure is a `RetriedBySenderException` that is
parked after one delivery (`MessagingAutoConfiguration.java:140`), and the payment's sweeper re-sends the check after
its 5 s deadline. A hung member is evicted at 10 s (`HazelcastConfigFactory.java:39-42`). Cost: a parked record
sits on the dead-letter topic until someone looks, and with one listener thread an instance gets through about one
check per second in the outage, so queued checks may be re-sent.

## What breaks if... (exercise)

Suppose someone puts the attempt check back for RELEASING too, so the guard at `SagaStateMachine.java:51` reads
`replyAttempt != payment.stepAttempts()` for every state. Work out:

1. A release is sent as attempt 1 and the wallet answers slower than the 10 s deadline. The sweeper re-sends as
   attempt 2, then 3. The wallet's answer to attempt 1 arrives. What does the payment do with it, and with the answers
   to attempts 2 and 3 that arrive later?
2. Once the deadline reaches the 30 s cap, what does the sweeper do next, and how often? In which state is the payment
   forever, and who still holds money?
3. Which test goes red, and with what message (hint: `SagaModelPropertyTest` liveness, line 202, with latency 1 to 4)?
   Why was the first version of the test green?

Check on a scratch branch with `mvnw.cmd -q -B clean test -pl payment-service -am -Dtest=SagaModelPropertyTest`, then
revert.

## TODO-ME

`payment-service/src/test/java/com/siddheshfulawade/payflow/payment/domain/ReleaseAttemptEdgeTest.java`: a disabled,
pure unit test. Outside RELEASING a reply counts only for the current attempt: on a RISK_CHECKING payment at attempt 2,
`RiskApproved` of attempt 1 is ignored and attempt 2 is accepted. What to do and how to check it are in the comment
above the test (marked `// TODO-ME:`). It is `@Disabled`, so the build stays green while it is unfinished. This is the
only TODO-ME of PF-06. The class is called `ReleaseAttemptEdgeTest`, but the test is about attempts outside RELEASING;
the name was kept because it was already committed. The exception for RELEASING is the contrast to think about.
