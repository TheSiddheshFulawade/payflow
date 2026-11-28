-- REQ-PF-042 measured run: the hold row of each seeded payment, consistent with it (CAPTURED / RELEASED / REJECTED).
insert into hold (payment_id, payer, amount, state, reason, payee, updated_at)
select md5(:'tag' || '-' || g)::uuid, 'recon-measured-payer', 1 + (g % 50000),
       case when g % 10 = 0 then 'RELEASED' when g % 10 = 1 then 'REJECTED' else 'CAPTURED' end,
       case when g % 10 = 1 then 'INSUFFICIENT_FUNDS' end,
       case when g % 10 in (0, 1) then null else 'recon-measured-payee' end,
       (:'day' || ' 10:00:00+00')::timestamptz + (g % 36000) * interval '1 second'
from generate_series(1, :n) g;
