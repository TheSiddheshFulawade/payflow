---
name: messaging-patterns
description: The PayFlow messaging and saga patterns - outbox, relay, inbox, idempotent handlers, the orchestrator state machine, timeouts, the sweeper, Kafka settings and Hazelcast entry processors. Load before any Kafka, saga or risk velocity work.
---

# Messaging patterns (PayFlow)

## Envelope (`contracts`)

Every message: `messageId` (UUID, unique per send), `paymentId` (Kafka key), `type`, `version` (int, starts at 1),
`attempt` (the step attempt it belongs to), `occurredAt`, `payload`. JSON via Jackson. Unknown fields tolerated on
read (forward compatibility); a missing required field is a poison message (log, park in `*.dlt`, alert metric).

Topics: `wallet.commands`, `wallet.replies`, `risk.commands`, `risk.replies`. 3 partitions each, key `paymentId`.

## Outbox (write side)

```
BEGIN; update business row; insert into outbox(id, topic, key, payload, created_at); COMMIT;
relay: BEGIN; select … from outbox where sent_at is null order by created_at limit 100 for update skip locked;
       send each (acks=all, idempotent producer), wait for the acks; update sent_at; COMMIT;
```

A crash after the send and before the `sent_at` update sends the message again. That's safe because consumers dedupe
by `messageId`, and why consumers must be idempotent. Several relays can run at once (`SKIP LOCKED`). Delete sent
rows older than 7 days.

## Inbox (read side)

```
BEGIN; insert into inbox(message_id) on conflict do nothing → 0 rows? duplicate: re-send stored reply, COMMIT;
       apply effect; store reply in outbox (and its id against the inbox row); COMMIT; then commit the Kafka offset
```

## Orchestrator rules

- One row per payment: `state`, `step_attempts`, `deadline_at`, `version`. Every transition is
  `UPDATE … SET state=?, version=version+1 WHERE id=? AND version=?`; 0 rows means someone else moved it, so reload.
- Act on a reply only if `reply.type` belongs to the current state and `reply.attempt` is the current attempt. One
  exception: while `RELEASING`, the wallet's final answers (`FundsReleased`, `FundsCaptured`) count for any attempt,
  because a release is re-sent forever and a slow wallet always answers an older one.
- Each transition writes the next command to the outbox in the same transaction.
- Sweeper: every 2 s, read up to 50 overdue ids without a lock (`deadline_at < now`, oldest first), then one
  transaction per id: `select … where id = ? and deadline_at < now and state in (in-flight) for update skip locked`.
  Re-send the current command (same payload, new message id, attempt + 1). 3 re-sends (attempts 1..4), then
  `RELEASING`. Releases are retried forever with capped backoff and a WARN log (from attempt 4), not an alert metric.
  A release answered `FundsCaptured` ends COMPLETED. A payment whose command is still unsent in the outbox
  (`sent_at is null`) is postponed (same attempt, new deadline, no command), not re-sent: only a published command can
  be late.

## Kafka settings

Producer: `acks=all`, `enable.idempotence=true`, `delivery.timeout.ms` 30 s. Consumer: `enable.auto.commit=false`,
`isolation.level=read_committed` (harmless without transactions), manual ack after the DB commit, `max.poll.records` 50. Not used: Kafka transactions. They give exactly-once only from Kafka to Kafka, and our effects live in
PostgreSQL, so outbox + inbox is the right tool (ADR-0002).

## Hazelcast velocity (risk)

`IMap<String payerId, VelocityWindow>`, where the window holds `Map<paymentId, epochMillis>`. A
`VelocityEntryProcessor(paymentId, now, limit, windowMs)` runs on the key's owner: it drops entries older than the
window, adds the paymentId if absent (so a duplicate is free), and returns whether the count is within the limit.
It's atomic per key without locks. The map has a TTL of 2 × window. Two members are discovered by TCP-IP (multicast
off). Losing one member keeps the data, because the backup count is 1.
