-- risk-service owns these tables (V1 is the shared outbox and inbox from messaging).
-- The CHECKs are the last line of defence: the code must never produce a state they refuse (REQ-PF-031).

-- One row per payment: the permanent decision. The primary key is what serialises all checks of one payment.
-- PENDING exists only inside the transaction that is deciding; APPROVED and REJECTED are final.
create table risk_decision (
    payment_id uuid        primary key,
    -- what was decided, so a check for other money can be told from a repeat
    payer      text        not null,
    -- minor units
    amount     bigint      not null,
    decision   text        not null,
    rule       text,
    decided_at timestamptz not null default now(),
    constraint risk_decision_amount_positive check (amount > 0),
    constraint risk_decision_known check (decision in ('PENDING', 'APPROVED', 'REJECTED')),
    constraint risk_decision_rule_known check (rule in ('SINGLE_LIMIT', 'DAILY_LIMIT', 'VELOCITY')),
    constraint risk_decision_rule_only_when_rejected check ((decision = 'REJECTED') = (rule is not null))
);

-- Approved amounts per payer and UTC date. The row lock makes the payments of one payer take turns.
create table daily_total (
    payer         text   not null,
    business_date date   not null,
    -- minor units
    amount        bigint not null,
    primary key (payer, business_date),
    constraint daily_total_amount_not_negative check (amount >= 0)
);
