# PF-04: risk-service, rules, velocity in Hazelcast, and a decision that is made once

Code: `risk-service/src/main/java/com/siddheshfulawade/payflow/risk/`. Requirements: REQ-PF-031 and 032 in SPEC.md.
Status of the tests, stated honestly: the unit tests and the two-member Hazelcast tests ran green locally
(`HazelcastVelocityClusterTest` 15/15, plus five extra repeats). Risk's `-Pit` tests (SQL, Kafka, handler) have run
NOWHERE yet, because Docker failed again. CI `integration` will be the first run. Do not quote them as passing.

## What the code does, in plain words

A `CheckRisk` command arrives on `risk.commands` (`RiskCommandListener.java:31-34`). The handler answers
`RiskApproved` or `RiskRejected(rule)`, and the answer goes to the outbox, never straight to Kafka.

Three rules, in this order (`RiskCommandHandler.java:114-126`, limits in `RiskLimits.java:22-24`):

1. **SINGLE_LIMIT**: one payment over 5,000,000 minor units (`RiskLimits.java:26-28`). Limits are inclusive: exactly
   5,000,000 passes.
2. **DAILY_LIMIT**: the payer's approved total for the UTC day plus this payment over 20,000,000
   (`RiskLimits.java:31-33`).
3. **VELOCITY**: more than 5 distinct payments by the payer in 60 s (`VelocityWindow.java:34-44`).

The first rule that fails names the rejection. If none fails, the amount is added to the day's total and the
decision becomes APPROVED (`RiskCommandHandler.java:107-110`).

### Why velocity is last

Velocity is the only rule with a side effect outside the database: an allowed check takes a slot in the Hazelcast
window (`VelocityWindow.java:39-42`). A payment that is refused by the single or daily rule would waste a slot if
velocity ran first, and a payer could be blocked by payments that were never going to be approved. Running the cheap,
pure rules first means a refused payment takes no slot (`RiskCommandHandler.java:113`). The tests that name this:
`RiskRulesTest` ("never asks the velocity counter", line 189).

## The decision is permanent (PENDING claim, the primary key is the lock)

First thing the handler does is `claim`: `insert into risk_decision ... 'PENDING' on conflict (payment_id) do nothing`
(`JdbcRiskDecisions.java:27-35`, `RiskCommandHandler.java:97`). If it inserted 1 row, we own this payment and go on
to decide. If 0 rows, someone already decided it: answer from the stored row (`RiskCommandHandler.java:129-143`).

Spring Boot / Oracle analogy: a unique key as a "who got here first" lock. If two transactions insert the same key,
the second one waits until the first commits or rolls back. Oracle does the same with a unique index. After a commit
the second gets "conflict, do nothing"; after a rollback its insert goes ahead (comment at `JdbcRiskDecisions.java:25-26`).
So the primary key (`V2__risk.sql:6-7`) is both the dedupe and the per-payment lock, with no extra locking code.

PENDING lives only inside the transaction that is deciding (`V2__risk.sql:4-5`). It is updated once, from PENDING to
APPROVED or REJECTED (`JdbcRiskDecisions.java:57-67`, `where ... decision = 'PENDING'`), and never deleted. A
re-sent command (the saga sweeper uses a new message id, so the inbox cannot see it) is answered from the row, not
from today's totals. That is why the answer does not change when the day's total has grown since.

The row also stores payer and amount. A repeat for other money is not a repeat: `decision.isFor(...)` fails and the
handler throws `PoisonMessageException` with a fixed text (`RiskCommandHandler.java:135-137`). It goes to the
dead-letter topic, not into a RiskApproved for the wrong amount.

## The daily total: lock the row, so create it at 0 first

`JdbcDailyTotals.lock` (`JdbcDailyTotals.java:18-31`) does two statements:

1. `insert ... values (payer, date, 0) on conflict do nothing` (lines 20-22).
2. `select amount ... for update` (lines 24-30).

Why the insert first: `select for update` locks rows that exist. If the payer has no row yet, two first payments of
the day would both see "no row", both read a total of 0, and both pass. By creating the row at 0 first, both fight for
one real row; the second waits for the first to commit and then reads the first one's amount. Comment at line 19.

The check is `exceedsDaily(total, amount)` = `amount > daily - total` (`RiskLimits.java:32`), written so that a huge
amount cannot overflow a `long`. `add` repeats the limit inside SQL (`and amount + :amount <= :limit`,
`JdbcDailyTotals.java:36-44`). If the Java were wrong, 0 rows change and the transaction rolls back. The table
CHECK `amount >= 0` (`V2__risk.sql:28`) is the last line.

**UTC date**: `LocalDate.ofInstant(now, ZoneOffset.UTC)` (`RiskCommandHandler.java:101`). One fixed rule for "the
day", no daylight saving, no setting that could differ between members. Only approved amounts count (line 107), and
a refund does not give headroom back: the total is only ever added to, so the limit means "approved today".

Lock order: the payment's decision row first, then the payer's daily total (`RiskCommandHandler.java:31-32`).
Everyone takes them in the same order, so two checks never wait for each other in a circle (no deadlock).

## Hazelcast basics, for someone who knows Kafka

- **Cluster, member**: each risk-service instance embeds a Hazelcast member in its JVM (`RiskConfig.java:21-24`).
  Members find each other by a fixed list of addresses (`HazelcastConfigFactory.java:48-51`), no multicast.
- **Partition**: a map is cut into 271 partitions (the default), like Kafka partitions of a topic. The key
  (here the payer) picks the partition by hash.
- **Owner and backup**: every partition lives on one member, the owner, and a copy on another, the backup
  (`setBackupCount(1)`, `HazelcastConfigFactory.java:60`). Backup is synchronous (`setAsyncBackupCount(0)`, line 61):
  the call returns only after the backup has the write, so a counted payment is on two members before it is answered.
- **IMap**: a distributed `Map<K,V>`. Here `IMap<String, VelocityWindow>`, key = payer (`HazelcastVelocityCounter.java:23`).
- **EntryProcessor**: instead of get, change, put (a race between two members), you ship a small function to the
  owner of the key and it runs there. The owner runs processors for one key one after another, so there is no
  read-modify-write race (`VelocityEntryProcessor.java:9-11`). It is like `update ... set x = x + 1` in SQL
  instead of a read and a write in Java.

### Why distinct payment ids make redelivery free

The window holds payment id to first-allowed time (`VelocityWindow.java:9`). A check for an id already in the window is
allowed and changes nothing (`VelocityWindow.java:37-42`), even when the window is full. A redelivery, a sweeper
re-send, or a retry after a rollback asks again for the same id and finds its own slot. A plain counter
("count + 1") would count it twice. A repeat also keeps its first timestamp, so it cannot extend its own life
(`VelocityWindowTest.java:93-108`).

### Determinism: the caller passes the time

The backup member runs the same processor on its copy (`getBackupProcessor` returns the processor itself by default;
comment at `VelocityEntryProcessor.java:12-14`). If the processor read the clock, owner and backup would compute
different windows. So `now` comes from the caller (`HazelcastVelocityCounter.java:36`), and `VelocityWindow` has no
clock (`VelocityWindow.java:11-12`). It also makes tests exact: `MutableClock` in the tests moves time by hand.

A refused or repeated check writes nothing (`VelocityEntryProcessor.java:25-27`), because a write would restart the
entry's time-to-live.

### TTL = 2 x window

`HazelcastConfigFactory.java:55-62`: `ttl = ceil(2 x window)` in seconds. Entries for idle payers must go away, or the
map grows forever. Two windows and not one, so an entry outlives its newest payment even if two members' clocks
differ a little. Rounded up because a TTL of 0 means "never expires". It follows the configured window, so a longer
window never gets a shorter TTL (`HazelcastVelocityClusterTest`, "TTL follows the window", line 345).

## The call inside the database transaction

The velocity call happens while the handler holds the decision row and the daily-total lock
(`RiskCommandHandler.java:34-37`, called from line 123). That looks wrong at first: a network call inside a
transaction. The reason is fail-closed. If the call comes after the commit, a lost or unknown count means the payment
was already approved with no slot taken, so a payer could pass the limit. If it comes before the commit, a failure
throws, everything rolls back, and Kafka redelivers. Counting for a transaction that then rolls back is harmless,
because the retry finds its own slot (distinct ids).

The cost: while we wait, we hold row locks and a Kafka partition. So the wait is bounded:

- `CALL_TIMEOUT = 5 s` (`HazelcastVelocityCounter.java:21`) is enforced with `orTimeout` (lines 41-44). Hazelcast's own
  call timeout keeps waiting as long as the owner still sends heartbeats, so it is not a hard bound; the
  `hazelcast.operation.call.timeout.millis` property is also set to 5 s (`HazelcastConfigFactory.java:27-29`) as a second
  guard against a gone owner.
- On timeout or any error, `join()` throws, the transaction rolls back, nothing is approved. That is **fail closed**.
  `HazelcastFailureTest` (line 35) proves it never approves and never hangs.
- A check that runs on the cluster after we gave up is harmless: the retry asks for the same payment id.

Consequence for the saga (for PF-06): with velocity down, one command blocks its partition for about 55 s
(about 10 tries of 5 s, with the 500 ms retry back-offs, README Decisions) while the orchestrator's step deadline is 5 s.

## Losing a member

With one backup, if the owner dies the backup is promoted to owner and a new backup is made. The synchronous backup
means every allowed payment was already on the survivor. `survivorKeepsTheCount` (line 214) and
`backupStaysConsistentUnderLoad` (line 237) test this, for a graceful shutdown and a kill. During the switch a
call can fail with "partition is migrating"; Hazelcast retries it, which is why its own exception classes are in the
deserialization allow-list (`HazelcastConfigFactory.java:71-82`). Without them the error cannot be read and the
check hangs until the 5 s bound instead of being retried.

Spring shuts the member down gracefully (`destroyMethod = "shutdown"`, `RiskConfig.java:21`) so it hands over its
partitions; Hazelcast's own JVM hook is off (`HazelcastConfigFactory.java:30-32`).

## Split-brain: what we accept

If the network splits the two members, each side thinks it is alone and counts for itself. A payer could then get up
to twice the limit (10 in 60 s instead of 5). When the sides merge, one side's window wins
(`HazelcastConfigFactory.java:14-16`). We do not add split-brain protection (that needs a quorum, which two members
cannot give without losing availability). It is acceptable because velocity is a risk control, a speed bump, not a
money invariant. The single and daily limits and the decision live in PostgreSQL and stay exact. Wallet balances
never depend on it.

## Java deserialization filter

Hazelcast sends the processor and the window between members using Java serialization. Java deserialization of
attacker-chosen classes is a classic remote-code-execution risk (gadget chains). So we set an allow-list
(`HazelcastConfigFactory.java:66-83`): our own package prefix, `UUID`, `Long`, `Number`, `java.util.CollSer`
(how `Map.copyOf` is serialized), and a few exception classes. `setDefaultsDisabled(true)` removes Hazelcast's
defaults, which allow all of `java.*`. The network side is closed too: join by TCP-IP to listed members only, no
multicast, no auto-detect, a fixed port that fails if taken, and a bind address that is not `0.0.0.0`
(`HazelcastConfigFactory.java:41-52`; `hazelcast.socket.bind.any=false`). `foreignClassesCannotBeDeserialised` (line
372) and `payflowClassesStillDeserialise` (line 383) check both directions: not too loose, not too tight.

## Text sequence diagram

```
Kafka risk.commands     RiskCommandListener     IdempotentConsumer (one DB tx)     Handler          PostgreSQL        Hazelcast owner
      | CheckRisk(pay1)          |                          |                          |                 |                   |
      |------------------------->| decode                   |                          |                 |                   |
      |                          |------------------------->| begin tx; inbox insert   |                 |                   |
      |                          |                          |------------------------->| claim(pay1)     |                   |
      |                          |                          |                          |---------------->| insert PENDING   |
      |                          |                          |                          |<----------------| 1 row (ours)     |
      |                          |                          |                          | single limit ok (pure Java)         |
      |                          |                          |                          |---------------->| insert daily row 0|
      |                          |                          |                          |---------------->| select ... for update
      |                          |                          |                          |<----------------| total          |
      |                          |                          |                          | daily limit ok  |                   |
      |                          |                          |                          |-------------------- submitToKey(payer, processor(pay1, now))
      |                          |                          |                          |                 |   run, setValue, backup copy
      |                          |                          |                          |<-------------------- true (within 5 s, else throw: rollback)
      |                          |                          |                          |---------------->| daily total += amount
      |                          |                          |                          |---------------->| PENDING -> APPROVED
      |                          |                          |<-------------------------| RiskApproved    |                   |
      |                          |                          | outbox insert; commit    |                 |                   |
      |<-- offset commit --------|<-------------------------| return                   |                 |                   |
      outbox relay later sends RiskApproved to the reply topic
```

Redelivery after the commit: the inbox row exists, the stored reply is re-sent, no handler call. Re-send with a new
message id: `claim` returns 0 rows, the stored decision answers, velocity is not called.

## Five interview questions

**1. Why is velocity the last rule, and why is the Hazelcast call inside the database transaction?**
Last, because it is the only rule with a side effect outside the database (a slot in the window), and a payment refused
by the single or daily rule must take no slot (`RiskCommandHandler.java:113`). Inside the transaction, so that failure
is closed: if the count is lost, nothing is approved, the transaction rolls back and Kafka redelivers. Counting
after the commit would approve without a count. The price is holding locks while we wait, so the wait is bounded at
5 s (`HazelcastVelocityCounter.java:21`).

**2. A CheckRisk is delivered twice, once with a new message id by the sweeper. What happens?**
The inbox cannot see the second (new message id). `claim` inserts into `risk_decision` with `on conflict do nothing`
and gets 0 rows (`JdbcRiskDecisions.java:27-35`), so the handler answers from the stored row
(`RiskCommandHandler.java:129-143`): same verdict, no second daily-total add, no second velocity call. If both copies
run at the same time, the second blocks on the primary key until the first commits. If the second has other payer or
amount, it is a poison message (line 135).

**3. Why does `daily_total.lock` insert a row of 0 before `select for update`?**
`for update` locks only existing rows. With no row, two concurrent first payments of the day would both read "nothing,
so 0" and both pass the limit. Creating the row first (`on conflict do nothing`) gives them one real row to
queue on (`JdbcDailyTotals.java:19-30`). The second then reads the committed total of the first.

**4. What is an EntryProcessor, and why must it be deterministic?**
A function that Hazelcast ships to the member that owns the key and runs there, one at a time per key, so there is
no get/change/put race across members. The backup member runs the same function on its copy, so the function must give
the same result: no clock, no randomness. That is why the caller passes `nowMillis`
(`VelocityEntryProcessor.java:12-15`, `HazelcastVelocityCounter.java:34-38`). Reading the clock inside would let owner
and backup disagree about the window.

**5. The network splits the two members. What can go wrong, and why do you accept it?**
Each side counts alone, so a payer can send up to twice the limit while the split lasts, and on merge one window wins
(`HazelcastConfigFactory.java:14-16`). Accepted because velocity is a risk control, not a money invariant: the single
limit, the daily limit and the permanent decision are in PostgreSQL and stay exact. Strict protection would need a
quorum or the CP subsystem, which costs availability for a speed bump. The alternative is stated in the README
Decisions.

## What breaks if... (exercise)

Suppose someone moves the velocity check to the first rule, so the order is velocity, single, daily. Work out, with
the code open at `RiskCommandHandler.java:114-126` and `VelocityWindow.java:34-44`:

1. A payer sends 6 payments of 6,000,000 within a minute. Which rules fire, and how many slots does the window use
   compared with today's order?
2. Which tests in `RiskRulesTest` (lines 157-200) go red, and why?
3. Then suppose the daily-total insert at `JdbcDailyTotals.java:20-22` is removed. Write down the exact interleaving
   of two first payments of a day that now both pass the daily limit.

Check your answers by making the change on a scratch branch and running
`./mvnw -q -B clean test -pl risk-service -am` (unit tests) to see what goes red. Revert it afterwards. Part 3 needs
`-Pit` (a real database), so reason it through on paper unless CI has run.

## TODO-ME

`risk-service/src/test/java/com/siddheshfulawade/payflow/risk/domain/RiskLimitsEdgeTest.java`: a disabled test about the
daily limit at its edges and the overflow-safe comparison. The instructions are in the file.
