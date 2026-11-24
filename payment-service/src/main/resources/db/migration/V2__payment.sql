-- payment-service owns these tables (V1 is the shared outbox and inbox from messaging).
-- The CHECKs are the last line of defence: the code must never produce a row they refuse.

create table payment (
    id              uuid        primary key,
    client_id       text        not null,
    idempotency_key text        not null,
    -- SHA-256 of the request, so a repeated key with another body can be told from a retry (REQ-PF-002)
    request_hash    text        not null,
    payer           text        not null,
    payee           text        not null,
    -- minor units
    amount          bigint      not null,
    currency        text        not null,
    state           text        not null,
    -- the attempt of the current step; only a reply with this attempt counts (0 before the first step)
    step_attempts   integer     not null,
    -- when the current step is overdue
    deadline_at     timestamptz,
    -- optimistic lock: every transition adds 1 and only updates the version it read (REQ-PF-014)
    version         bigint      not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    -- the database, not the code, decides which of two concurrent creates wins (REQ-PF-002)
    constraint payment_idempotency_key_unique unique (client_id, idempotency_key),
    constraint payment_amount_in_range check (amount between 1 and 10000000),
    constraint payment_currency_inr check (currency = 'INR'),
    constraint payment_not_to_self check (payer <> payee),
    constraint payment_state_known check (state in (
        'RECEIVED', 'RESERVING', 'RISK_CHECKING', 'CAPTURING', 'COMPLETED', 'RELEASING', 'REFUNDED', 'REJECTED')),
    constraint payment_step_attempts_not_negative check (step_attempts >= 0),
    constraint payment_version_not_negative check (version >= 0),
    -- an in-flight payment without a deadline would be invisible to the sweeper, i.e. stuck; a finished payment with
    -- one would be swept
    constraint payment_deadline_only_in_flight check (
        (state in ('RESERVING', 'RISK_CHECKING', 'CAPTURING', 'RELEASING')) = (deadline_at is not null))
);

-- Audit of every transition, append-only, written in the same transaction as the state change. Re-sending the current
-- step's command (same state, next attempt) is not a transition and writes no row here.
create table payment_event (
    -- insertion order
    id         bigint      generated always as identity primary key,
    payment_id uuid        not null references payment (id),
    -- null for the first event
    from_state text,
    to_state   text        not null,
    -- the reply that caused the transition and its attempt; null when no reply did (the API request, later a timeout)
    message_id uuid,
    attempt    integer,
    -- why a payment failed; null for a successful step
    reason     text,
    created_at timestamptz not null,
    constraint payment_event_message_has_attempt check ((message_id is null) = (attempt is null))
);

-- The saga has no loops: a payment enters each state at most once, even if the code were wrong. Also the lookup by
-- payment.
create unique index payment_event_state_once_idx on payment_event (payment_id, to_state);

-- ... and at most one terminal state (REQ-PF-010).
create unique index payment_event_one_terminal_idx on payment_event (payment_id)
    where to_state in ('COMPLETED', 'REFUNDED', 'REJECTED');
