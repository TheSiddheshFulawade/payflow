# PF-02: outbox, relay, inbox, and why a resend is safe

Code: `contracts/` (message types, codec) and `messaging/` (outbox, relay, inbox, Kafka settings).
Requirements: REQ-PF-020 to 022 in SPEC.md. Decision record: ADR-0002 #5.

## The problem: the dual write

A service must change its database (reserve funds) and tell Kafka about it (send `FundsReserved`). These are two
systems, so there is no single commit. Whichever you do first, a crash between the two leaves them out of step:

- DB first, crash, no message: the money is held and the payment never hears about it.
- Kafka first, crash, no DB change: a message announces something that never happened.

## What the code does, in plain words

1. **Outbox write.** `OutboxWriter.append` inserts the message as a row in the `outbox` table, in the same
   transaction as the state change (`OutboxWriter.java:28`, `Propagation.MANDATORY`: with no transaction it throws
   instead of writing an orphan). The key is the payment id, so one payment stays on one partition, in order
   (line 34). The table is in `V1__messaging_outbox_inbox.sql`.
2. **Relay.** `OutboxRelay.relayOnce` (`OutboxRelay.java:51`) runs in one DB transaction. It selects unsent rows with
   `for update skip locked` (lines 58-64), sends each to Kafka (line 76), waits for every ack (line 79), and only
   then runs `update outbox set sent_at = now()` (lines 82-84). The producer is `acks=all` with idempotence
   (`KafkaClientSettings.java`, `PRODUCER_REQUIRED`), so an ack means every in-sync replica has the record.
   `SKIP LOCKED` means a second relay skips rows the first has locked rather than waiting or sending them twice
   (test: `OutboxRelayTest`, two relays, 200 rows, each sent once).
3. **Inbox.** `IdempotentConsumer.handle` (`IdempotentConsumer.java:47`), in one transaction, runs
   `insert into inbox ... on conflict (message_id) do nothing` (line 51). One row inserted means first delivery: run
   the effect, append the reply to the outbox, remember the reply's outbox id (lines 64-70). Zero rows means a
   duplicate: no effect, but `resendStoredReply` (line 79) copies the stored reply into a new outbox row with the
   same payload, so the same message id goes out again. Why re-send: a duplicate command usually means the sender
   never saw our reply.
4. **Offset after commit.** The container ack mode is `RECORD` (`MessagingAutoConfiguration.java:122`) and
   auto-commit is off (`KafkaClientSettings`, `CONSUMER_REQUIRED`), so the offset is committed only after the
   listener returned, which is after the DB commit (REQ-PF-022). A crash in between redelivers the record, and the
   inbox absorbs it.
5. **Poison and retry.** A record that cannot be decoded throws `PoisonMessageException`
   (`MessageCodec.java:77-78`). The error handler (`MessagingAutoConfiguration.java:130-134`) never retries it and
   parks it on the dead-letter topic (DLT) at once. Other failures (a database blip) are retried every 500 ms, 9
   times after the first try (about 5 s), then go to the DLT. A longer outage is covered by the saga deadline and
   sweeper, which re-send the command; handlers are idempotent, so that is safe.
6. **Closed type registry.** `MessageCodec.TYPES` (`MessageCodec.java:28`) is built from the sealed `Command` and
   `Reply` permits. The JSON `type` is only a lookup key into that map (line 64); an unknown name is poison. Jackson
   "default typing" instead lets the JSON name any Java class to instantiate, which is the classic
   deserialization-gadget attack: anyone who can write to the topic picks a class whose constructor or setter does
   harm. Here the set of classes is fixed at compile time.
7. **No Kafka transactions.** Kafka exactly-once covers Kafka-to-Kafka. Our effect is a PostgreSQL row, which Kafka
   cannot commit, so the dedupe must live in the same DB transaction as the effect (ADR-0002 #5).

## Core concept

Delivery is **at least once**; correctness comes from making the **effect idempotent**: at-least-once plus dedupe
by message id behaves like exactly-once for the database. Think of Spring `@Transactional` plus a unique key,
rather than a Kafka feature.

## Sequence: the crash test (`OutboxRelayTest.java:103`)

```
test        Outbox   Relay        Kafka      Consumer(wallet)   Inbox/effect
 |-append->  row(sent_at=null)
 |-relayOnce->|
 |            |--select ... skip locked
 |            |--send------------>| ack
 |            |  hook: CRASH (before sent_at)
 |            |  tx rolls back: sent_at still null
 |            |                   |--deliver cmd------>|--insert inbox(id) 1 row
 |            |                   |                    |--effect, reply -> outbox
 |-relayOnce->|  (restarted relay)
 |            |--select: same row again
 |            |--send------------>| ack (2nd copy, same messageId)
 |            |--update sent_at = now()  (commits)
 |            |                   |--deliver cmd------>|--insert inbox: 0 rows
 |            |                   |                    |--no effect; copy stored reply
 |            |--relay sends reply copies (same reply messageId)
assert: effect rows = 1, inbox rows = 1, command seen twice with one id,
        replies carry one messageId (copies allowed)
```

## Interview questions

1. **What is the dual-write problem and how does the outbox solve it?** Two systems cannot commit together. The
   outbox turns the second write into a row in the same database transaction as the state change, so both happen or
   neither; a separate relay moves rows to Kafka later.
2. **Why can the relay send a message twice, and why is that fine?** It marks `sent_at` only after the acks. A crash
   between ack and commit leaves the row unsent, so it goes again. Consumers dedupe on message id in the inbox, so
   the second copy has no effect. Losing a message is the unsafe direction, and this order never loses one.
3. **What does `SKIP LOCKED` give you?** Several relay instances can poll the same table; each takes rows nobody
   else holds, with no waiting and no duplicates from concurrency. Without it they would block each other or send
   the same rows.
4. **Why must the inbox insert be in the same transaction as the effect?** If it committed first and the effect
   failed, the retry would look like a duplicate and the effect would be lost. If it committed after, a crash would
   apply the effect twice. In one transaction both commit together, and a concurrent copy waits on the primary key.
5. **Why not Kafka exactly-once transactions, and why a dead-letter topic?** EOS only covers Kafka reads and writes,
   not a PostgreSQL update. The DLT parks a record that can never succeed (or failed 10 times) so one bad record
   does not block every payment on that partition; the sweeper re-sends commands parked by a transient outage.

## What breaks if...

You set `sent_at` before waiting for the acks (swap the steps at `OutboxRelay.java:79` and `:82`)? The row is marked
sent, then Kafka fails or the process dies before the broker has the record. The relay never looks at that row
again, so the message is lost: funds stay reserved and the saga waits until its deadline. Nothing duplicates, so a
test about duplicates would not notice. Exercise: predict which test catches it (hint: the crash test, with the hook
placed before the acks), then try it on a scratch branch and check.
