-- REQ-PF-042 measured run: N finished payments on the closed date :day, marked client_id "recon-measured".
-- 80 % COMPLETED, 10 % REVERSED, 10 % REJECTED; ids are md5-derived so the wallet seed names the same payments.
insert into payment (id, client_id, idempotency_key, request_hash, payer, payee, amount, currency, state,
                     step_attempts, deadline_at, version, created_at, updated_at)
select md5(:'tag' || '-' || g)::uuid, 'recon-measured', :'tag' || '-' || g, md5('h' || g) || md5('i' || g),
       'recon-measured-payer', 'recon-measured-payee', 1 + (g % 50000), 'INR',
       case when g % 10 = 0 then 'REVERSED' when g % 10 = 1 then 'REJECTED' else 'COMPLETED' end,
       1, null, 5, (:'day' || ' 10:00:00+00')::timestamptz,
       (:'day' || ' 10:00:00+00')::timestamptz + (g % 36000) * interval '1 second'
from generate_series(1, :n) g;
