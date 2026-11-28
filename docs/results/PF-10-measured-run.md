# PF-10 measured reconciliation run (REQ-PF-042)

Measured 2026-11-28 on the compose stack (`deploy/compose.yaml`), reproduce with `deploy/recon-measured/run.ps1`.

| Payments | Job time (Spring Batch) | Wall time incl. JVM start | Report |
| -------- | ----------------------- | ------------------------- | ------ |
| 10,000   | 2.4 s                   | 6.6 s                     | 10,000 MATCHED |
| 100,000  | 19.3 s (stage payments 6.7 s, stage wallets 6.6 s, report 6.0 s) | 22.6 s | 100,000 MATCHED |

**Machine:** AMD Ryzen 7 5800H (16 threads), 15.4 GB RAM, Windows 11, Docker Desktop 28.1.1 (8 GB VM). Job on the
host, JDK 25.0.1; services in containers (768 MB limit each), PostgreSQL 18.7 container (512 MB limit).

**Conditions, said plainly:**

- The rows were **seeded directly** into the payment and wallet databases (`seed-payment.sql`, `seed-wallet.sql`),
  not sent through the saga: the job only reconciles a closed date (≤ today − 2), so payments made through the saga
  on the day of measuring cannot be reconciled yet. The job itself is unchanged and real: it downloads both
  statements over HTTP with an ops token, stages them in chunks of 1,000 in the recon database, matches and writes
  the report.
- Mix: 80 % COMPLETED/CAPTURED, 10 % REVERSED/RELEASED, 10 % REJECTED/REJECTED; all consistent, so the expected
  report is MATCHED only. This measures throughput of a clean day, not the cost of finding breaks.
- One run each, warm stack; not a benchmark with repetitions or percentiles.
