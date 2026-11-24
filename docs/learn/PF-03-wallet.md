# PF-03: wallet-service, holds, and why money moves once

Code: `wallet-service/src/main/java/com/siddheshfulawade/payflow/wallet/`. Requirements: REQ-PF-030 and 021 in SPEC.md.
Status of the tests: the unit tests pass locally. The `-Pit` tests (database, Kafka, concurrency) have not run yet;
CI runs them, so do not quote them as passing until CI is green.

## What the code does, in plain words

A wallet has two numbers: `available` (you can spend it) and `held` (promised to a payment in flight). Total money
is `available + held`, and it must never change except when ops seed a wallet (`V2__wallet.sql:4-15`).

Three commands arrive on `wallet.commands` (`WalletCommandListener.java:191`):

- **Reserve**: move `amount` from available to held (`JdbcWallets.java:52-56`). Refused if the wallet is unknown,
  frozen or too poor (`WalletCommandHandler.java:94-98`, `Wallet.java:9-15`).
- **Capture**: take it out of the payer's held and add it to the payee's available (`JdbcWallets.java:65-74`). Total
  unchanged: one wallet loses, another gains, in one transaction.
- **Release**: give the held money back to available (`JdbcWallets.java:58-63`), when the payment is cancelled.

Each command produces a reply (`FundsReserved`, `FundsRejected`, `FundsCaptured`, `CaptureFailed`, `FundsReleased`)
that goes to the outbox, never straight to Kafka (`WalletCommandListener.java:171`).

## Two layers of idempotency

1. **Inbox, by message id** (PF-02). Kafka redelivers the same message: the inbox row already exists, so the effect is
   skipped and the stored reply is re-sent.
2. **Hold row, by payment id** (`hold` table, primary key `payment_id`, `V2__wallet.sql:19-34`). The saga sweeper
   re-sends an overdue command with a NEW message id (`WalletCommandHandler.java:30-31`), so the inbox sees a
   stranger. Without layer 2, a re-sent Reserve would hold the money twice. With it, `insertIfAbsent` returns false
   (`JdbcHolds.java:24-36`) and the handler answers from the stored decision (`WalletCommandHandler.java:82-84`,
   `Hold.java:32-41`).

Spring Boot analogy: the inbox is "have I seen this HTTP request id", the hold is "does this order already exist".
Different questions, both needed.

## The reserve decision is permanent

The first Reserve for a payment decides, and the row is never deleted (`WalletCommandHandler.java:146-150`). A later
copy is answered from the row, never from today's balance (`Hold.java:18-19`). Why: if the first Reserve said yes
and the wallet is now empty, a re-sent Reserve must still say yes, or the payment would see a contradiction. A
rejected hold stays rejected too ("sticky"; test `OutOfOrderTest.stickyRejection`, line 53).
Only `RESERVED` can change state (`JdbcHolds.java:65-76`, `where ... state = 'RESERVED'`).
The row also keeps `payee`, set only by the capture (`CAPTURED` if and only if `payee` is not null, a CHECK). A
re-sent command that contradicts the held or captured money (another payer, amount, or capture payee) is a poison
message, not a rejection: a rejection could end the saga REJECTED with money still held (`Hold.java:21-23`).

## The release tombstone

Two relays using `SKIP LOCKED` (PF-02) can publish rows out of order: relay A locks the older outbox row (Reserve),
relay B skips it and publishes the newer one (Release). The wallet then sees Release before Reserve. If it ignored
the unknown Release, the late Reserve would hold money for a payment that is already cancelled and finished REVERSED.
So Release with no hold inserts a `RELEASED` row, the tombstone (`WalletCommandHandler.java:100-106`), and the late
Reserve finds it and is rejected with `NO_ACTIVE_HOLD` (`Hold.java:34`). Test: `OutOfOrderTest.lateReserveAfterRelease`
(line 23).

## Locking, and the opposite-direction deadlock

Rule: lock the payment (its hold row) first, then the wallets in id order.

- The hold row's primary key serialises everything about one payment: a second insert waits until the first
  transaction ends (`JdbcHolds.java:25-27`); `lock` uses `for update` (`JdbcHolds.java:40-47`).
- Wallets are locked with `order by id for update` (`JdbcWallets.java:42`), so rows are locked in the same order by
  everyone.
- Without the order: "A pays B" locks A then waits for B; "B pays A" locks B then waits for A. Each waits for the
  other forever, and PostgreSQL aborts one with a deadlock error. With id order both lock A first, so one just waits.
  Capture locks both wallets (`WalletCommandHandler.java:140-144`). Test: `WalletConcurrencyTest` line 103.

## Relative guarded updates vs read-then-write vs a version column

- Read-then-write: read `available = 1000`, compute 970 in Java, write 970. Two transactions can both read 1000, and
  one write is lost.
- Optimistic version: write only `where version = :seen`; the loser gets 0 rows and must retry. Correct, but a busy
  wallet retries a lot.
- What we do: lock the row, check in Java, then `available = available - :amount ... and available >= :amount`
  (`JdbcWallets.java:53-55`). The database does the arithmetic on the current value, so no update overwrites another.
  The `version` column is only incremented, never checked.

## Three guards for the same rule (never negative)

1. Code: `Wallet.refusesReserve` (`Wallet.java:13-14`) answers INSUFFICIENT_FUNDS.
2. SQL `WHERE available >= :amount`; if 0 rows change, `Updates.exactlyOne` throws and the transaction rolls back
   (`JdbcWallets.java:79-80`, `Updates.java:6-10`).
3. Database: `CHECK (available >= 0)`, `CHECK (held >= 0)` (`V2__wallet.sql:12-13`), and a unique index on
   `ledger_entry (payment_id, kind)` so a payment cannot reserve, release or capture twice even if the code were
   wrong (`V2__wallet.sql:49`).

## Release on a CAPTURED hold replies FundsCaptured

If the money already reached the payee, replying `FundsReleased` would let the saga finish REVERSED although the
payee has the money. The truthful reply is `FundsCaptured` (`Hold.java:49`, test `ReleaseFundsTest.releaseOfCapturedHold`
line 111). Release also uses the hold's own payer and amount, not the command's (`WalletCommandHandler.java:112-114`).

## Sequence: a re-sent Reserve and a Release that overtakes it

```
Payment     Wallet listener         Inbox        hold table           wallet table
  |--Reserve(m1)-->|                  |               |                    |
  |                |--insert m1------>| new           |                    |
  |                |--insert hold (RESERVED)--------->| ok                 |
  |                |--lock payer, check, available-=, held+=--------------->|
  |                |--reply FundsReserved -> outbox, commit                 |
  |  (reply lost; sweeper re-sends with NEW id m2)                          |
  |--Reserve(m2)-->|--insert m2------>| new           |                    |
  |                |--insert hold: conflict (0 rows)->|                    |
  |                |--lock hold, answer FundsReserved, no money moves       |
  |
  |  (another payment, relays reorder: Release(m3) arrives before Reserve(m4))
  |--Release(m3)-->|--insert hold (RELEASED) = tombstone, reply FundsReleased
  |--Reserve(m4)-->|--insert hold: conflict -> HOLD is RELEASED -> FundsRejected(NO_ACTIVE_HOLD)
```

## Five interview questions

1. **Why two layers of idempotency?** The inbox stops a redelivered message (same id). The sweeper re-sends a command
   with a new id, so only a per-payment record (the hold row) can recognise it. Each answers a different duplicate.
2. **Why is the hold decision permanent instead of re-checking the balance?** A repeat must give the same answer as
   the first; the balance may have changed since. Re-checking could hold money twice or flip yes to no.
3. **What is the tombstone for?** Out-of-order delivery (two `SKIP LOCKED` relays) can put Release before Reserve.
   The tombstone makes the late Reserve fail instead of holding money for a finished payment.
4. **How do you avoid deadlocks between "A pays B" and "B pays A"?** Every transaction locks rows in one global
   order: payment first, wallets by id (`order by id for update`). No circular wait is possible.
5. **Why relative updates plus a SQL guard instead of read-then-write?** Under a row lock the database applies the
   change to the current value, so nothing is overwritten. The guard and the CHECK repeat the rule so a code bug
   rolls back loudly instead of writing a negative balance.

## What breaks if...

...you delete the `order by id` from `JdbcWallets.lock` (line 42)? Think through what `oppositeCapturesDoNotDeadlock`
does (`WalletConcurrencyTest.java:103`): which locks does each transaction hold, who waits, and what does PostgreSQL
do about it? Then say what Kafka and the inbox do with the failed message. (Check afterwards: one transaction is
aborted with a deadlock error and rolls back entirely, including its inbox row, so Kafka redelivers and it succeeds
later. The test would turn flaky, because the crossing only happens when two captures overlap in time.)

## TODO-ME

`WalletRefusalOrderTest` (`wallet-service/src/test/java/.../wallet/domain/`): a disabled test. Decide which refusal
a frozen and poor wallet gives and assert it. Instructions are in the file.
