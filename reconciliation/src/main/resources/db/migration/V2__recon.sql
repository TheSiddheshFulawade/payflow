-- reconciliation owns these tables. Every key starts with the business date, so one date's rows are one index range,
-- and the same payment id on another date is another row.

-- Downloaded statement lines, kept so a restarted job does not download again and the evidence of a run stays.
-- Written with "on conflict do update": staging a line twice leaves one row (REQ-PF-041).
create table recon_staging_payment (
    business_date date   not null,
    payment_id    uuid   not null,
    -- minor units
    amount        bigint not null,
    currency      text   not null,
    state         text   not null,
    primary key (business_date, payment_id),
    constraint staging_payment_state_known check (state in ('COMPLETED', 'REVERSED', 'REJECTED'))
);

create table recon_staging_wallet (
    business_date   date   not null,
    payment_id      uuid   not null,
    -- minor units
    captured_amount bigint not null,
    status          text   not null,
    primary key (business_date, payment_id),
    constraint staging_wallet_status_known check (status in ('CAPTURED', 'RELEASED', 'REJECTED'))
);

-- The report: one row per payment id and business date, MATCHED rows included. The primary key is what makes a restart
-- safe: the writer uses "on conflict do nothing", and even a wrong writer could not store a second row (REQ-PF-041).
create table recon_result (
    business_date date        not null,
    payment_id    uuid        not null,
    outcome       text        not null,
    created_at    timestamptz not null default now(),
    primary key (business_date, payment_id),
    constraint recon_result_outcome_known check (outcome in
        ('MATCHED', 'MISSING_IN_WALLET', 'MISSING_IN_PAYMENTS', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH'))
);
