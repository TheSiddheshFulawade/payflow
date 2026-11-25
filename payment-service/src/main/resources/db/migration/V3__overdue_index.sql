-- The sweeper looks for overdue payments every 2 s. Only in-flight payments have a deadline, so finished ones drop out
-- and the index stays small. "deadline_at < ..." implies "is not null", so the sweeper's query can use it.
create index payment_overdue_idx on payment (deadline_at) where deadline_at is not null;

-- For every overdue payment the sweeper asks whether its command is still unpublished. Published rows drop out. During
-- a broker outage the unsent rows are many, which is exactly when the sweeper asks most.
create index outbox_unsent_message_key_idx on outbox (message_key) where sent_at is null;
