-- Shared by every service (add classpath:db/messaging to spring.flyway.locations; service scripts start at V2).

-- Outbox: outgoing messages, inserted in the same transaction as the state change they announce (REQ-PF-020).
create table outbox (
    id          uuid        primary key,
    topic       text        not null,
    message_key text        not null,
    payload     text        not null,
    -- clock_timestamp(), not now(): rows appended in one transaction keep their order for the relay
    created_at  timestamptz not null default clock_timestamp(),
    -- null until Kafka has acked the message
    sent_at     timestamptz,
    -- an empty topic or key can never be published (or would lose per-payment ordering): refuse it at insert
    constraint outbox_topic_key_not_empty check (topic <> '' and message_key <> '')
);

-- The relay only looks for unsent rows, oldest first; sent rows drop out, so the index stays small.
create index outbox_unsent_created_at_idx on outbox (created_at) where sent_at is null;

-- Inbox: one row per consumed message id, inserted in the same transaction as its effect (REQ-PF-021).
-- Rows are kept forever on purpose: the dedupe is permanent, so a redelivery or a re-sent command of any age has no
-- second effect. Rows are small (uuid + timestamps); a retention window (e.g. 90 days, far beyond Kafka retention and
-- the saga's retry horizon) can come later if the table grows.
create table inbox (
    message_id      uuid        primary key,
    received_at     timestamptz not null default now(),
    -- the outbox row of the reply, so a duplicate can re-send exactly the same reply
    reply_outbox_id uuid
);
