-- Test-only business table: one row per applied ReserveFunds, so tests can count effects.
create table effect (
    payment_id uuid not null,
    message_id uuid not null
);
