-- wallet-service owns these tables (V1 is the shared outbox and inbox from messaging).
-- The CHECKs are the last line of defence: the code must never produce a state they refuse (REQ-PF-030).

create table wallet (
    id        text    primary key,
    owner_sub text    not null,
    -- minor units
    available bigint  not null,
    held      bigint  not null,
    status    text    not null,
    version   bigint  not null default 0,
    constraint wallet_available_not_negative check (available >= 0),
    constraint wallet_held_not_negative check (held >= 0),
    constraint wallet_status_known check (status in ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- One row per payment: the permanent record of the reserve decision. The primary key is what serialises all
-- commands of one payment. Only RESERVED can change; RELEASED, CAPTURED and REJECTED are final.
create table hold (
    payment_id uuid        primary key,
    -- no foreign key: a hold REJECTED with WALLET_UNKNOWN, and the tombstone of a release that arrived before its
    -- reserve, may name a wallet that does not exist
    payer      text        not null,
    amount     bigint      not null,
    state      text        not null,
    reason     text,
    -- set by the capture, so a re-sent capture naming another payee can be told from a repeat
    payee      text,
    updated_at timestamptz not null default now(),
    constraint hold_amount_positive check (amount > 0),
    constraint hold_state_known check (state in ('RESERVED', 'RELEASED', 'CAPTURED', 'REJECTED')),
    constraint hold_reason_only_when_rejected check ((state = 'REJECTED') = (reason is not null)),
    constraint hold_payee_only_when_captured check ((state = 'CAPTURED') = (payee is not null))
);

-- Every movement of money, append-only: it explains every balance (statements, reconciliation).
create table ledger_entry (
    id         uuid        primary key,
    wallet_id  text        not null references wallet (id),
    -- null only for the ops seeding of a wallet
    payment_id uuid,
    kind       text        not null,
    amount     bigint      not null,
    created_at timestamptz not null default now(),
    constraint ledger_kind_known check (kind in ('SEED', 'RESERVE', 'RELEASE', 'CAPTURE_DEBIT', 'CAPTURE_CREDIT')),
    constraint ledger_payment_unless_seed check ((kind = 'SEED') = (payment_id is null)),
    constraint ledger_amount_positive check (amount > 0 or (kind = 'SEED' and amount = 0))
);

-- No payment moves money twice: each kind at most once per payment, even if the code were wrong. Also the lookup
-- by payment. (SEED rows have no payment id, so the index does not restrict them.)
create unique index ledger_entry_payment_kind_idx on ledger_entry (payment_id, kind);

-- A wallet's statement, in time order.
create index ledger_entry_wallet_idx on ledger_entry (wallet_id, created_at);
