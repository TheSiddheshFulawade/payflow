# PF-10 Reconciliation: do the two books agree?

Status: branch `feat/pf-10-reconciliation`, head `bd76ee7`. CI was 5/5 green on that commit, and the integration tests
ran in CI (`ReconJobRestartIntegrationTest` 10 tests, the two statement query ITs 2 each). The measured run
(REQ-PF-042, the timing for the README) is NOT done yet: it needs Docker locally and an N (default 10,000, the cut
line; `-Drecon.n=` to change it). Line numbers are from the working tree and may move. Paths are shortened:
`recon/` = `reconciliation/src/main/java/com/siddheshfulawade/payflow/recon/`.

## What the code does, in plain words

Payment-service keeps one book (payments) and wallet-service keeps another (holds on money). They are updated by
messages, so a bug or a crash could leave them disagreeing. Reconciliation is the nightly check: take both books for
one closed day, compare them payment by payment, and write down the result.

1. **Two statements, as CSV, ops only.** Payment-service lists its finished payments for a UTC date:
   `payment_id,amount,currency,state` (`payment-service/.../api/StatementController.java:22`). Wallet-service lists
   its settled holds: `payment_id,captured_amount,status` (wallet `StatementController.java:22`). Both need the `ops`
   role (`SecurityConfig.java:48` payment, `:47` wallet) and refuse a date before 2000-01-01 or after tomorrow with
   400 (`StatementController.java:40-43`). The wallet statement is derived from the `hold` table, not the ledger, so
   there is exactly one line per payment (wallet `infra/JdbcStatements.java:32-35`); captured money is the hold amount
   when CAPTURED, else 0. The payment statement takes the states COMPLETED, REVERSED, REJECTED (payment
   `infra/JdbcStatements.java:31-33`). A date is the UTC day of `updated_at`, as a half-open range.
2. **Five outcomes** (`recon/domain/Outcome.java`): MATCHED, MISSING_IN_WALLET, MISSING_IN_PAYMENTS, AMOUNT_MISMATCH,
   STATUS_MISMATCH.
3. **Status correspondence** (`recon/domain/StatementMatcher.java:152-159`): COMPLETED = CAPTURED;
   REVERSED = RELEASED, or REVERSED = a REJECTED hold with 0 captured; REJECTED = REJECTED. A payment with no wallet
   line is MISSING_IN_WALLET (`:141-143`), because every reserve writes a hold row. Amounts are compared only when the
   statuses correspond (`:144-149`), so a status difference wins.
4. **The one-day window.** A payment can finish at 23:59 and its hold settle at 00:01, so the two lines sit on two
   UTC days. For date D the payment lines of D are matched with the wallet lines of D-1, D and D+1
   (`StatementMatcher.java:47-70`; doc `:20-22`). The pair is reported once, on the payment line's date: a wallet
   line of D is reported MISSING_IN_PAYMENTS only if no payment line of D-1, D or D+1 has its id (`:65`).
5. **Only closed dates.** `requireClosed` (`recon/app/ReconciliationRun.java:108-115`) allows D at most today minus 2
   (UTC). Yesterday may still be settling past midnight, so its statement is not final.
6. **The Spring Batch job** (`recon/infra/ReconJobConfig.java:48-54`): step `stagePayments` (download and stage),
   `stageWallets`, `report` (match and write). Staging means copying the downloaded lines into our own tables so a
   restart does not re-download and the evidence stays (`V2__recon.sql`). Each step writes chunks of 1,000
   (`Chunks.java:9`), one transaction per chunk. The plain sequence `ReconciliationRun.run` (`:45-54`) does the same
   for tests.
7. **Restart without duplicate rows.** The report key is `(business_date, payment_id)` (`V2__recon.sql:34`) and the
   insert is `on conflict ... do nothing` (`JdbcReportStore.java:30`); staging uses `on conflict do update`
   (`JdbcStagingStore.java:33-37`). The first step of a fresh job clears the date's staging
   (`ReconJobConfig.java:62-67`, `ReconciliationRun.startFresh :57-60`), so a line that vanished from the statements
   cannot reach the report; the report itself is never cleared. A FAILED job restarts and skips completed steps; a
   COMPLETED instance for the same `businessDate` is refused by Spring Batch (test `completedDateAgain`).
8. **Safety on the download** (`recon/infra/HttpStatementSource.java`): any status but 200 fails (`:55-57`), a body
   over the size cap fails (`:72-83`), and a payment line in another currency than `payflow.recon.currency` stops the
   run (`ReconciliationRun.java:97-106`). A repeated payment id in one statement stops it too
   (`StatementMatcher.java:115-137`).
9. **The ops token** comes from the environment variable `RECON_OPS_TOKEN` (`application.yml:30`), is bound as
   `@NotBlank` (`ReconProperties.java:19`), and is masked in `toString` (`:23-25`). The size cap is `@Positive`
   (`:20`), default 64 MiB.

## Core concepts

### Idempotent writes, so "run it again" is always safe
You know idempotent consumers from Kafka: the same message twice has the effect of once. Here the same row twice has
the effect of once, and the database enforces it with a primary key. A crash in the middle then needs no special code:
run again, the missing rows are added, the existing ones ignored.

### Chunked transaction (Spring Batch)
Read many items, write them 1,000 at a time, one transaction each. A failure loses at most the current chunk, not the
whole run, and memory use per write stays small. Think of it as `saveAll` in batches of 1,000 with a commit between.

### A placeholder is text, not a decision
With the token variable unset, a plain `${RECON_OPS_TOKEN}` placeholder stayed as the literal text, which is not blank,
so `@NotBlank` passed, the job started and only found out with HTTP 401 at the download. Now the default is empty
(`application.yml:29-30`), so the start fails at once. Test:
`ReconPropertiesTest.unsetEnvironmentVariableFailsWithShippedConfig`.

### The CI lesson
Maven Surefire runs only classes named `*Test`, `*Tests`, `*TestCase` (and `Test*`). Classes named `*IT` were silently
never run, so they proved nothing. Commit `bd76ee7` renamed them to `*IntegrationTest`. A green build is only evidence
for the tests that actually ran: check the test count, not just the colour.

## Sequence diagram

```
Operator    ReconJob         payment-svc     wallet-svc      recon DB
   | run date=D  |                |               |              |
   |------------>| requireClosed(D <= today-2)   |              |
   |             | clear staging(D)---------------------------->|
   |             | GET /ops/statements?date=D --->|              |
   |             |<-- CSV (200 only, size capped) |              |
   |             | stage in chunks of 1,000 ----------------------->| (1 tx / chunk)
   |             | GET /ops/statements?date=D ------------------>|
   |             |<-- CSV                                         |
   |             | stage in chunks ---------------------------->|
   |             | download D-1, D+1 (lookup only, not staged)   |
   |             | match(D, window D-1..D+1)                     |
   |             | insert recon_result ... on conflict do nothing->|
   |<- COMPLETED |  (crash anywhere: relaunch same date, resumes) |
```

## Five interview questions

1. **Why reconcile at all if the saga and the outbox already give exactly-once effect?** Model answer: those
   guarantees are per message and rely on our code being right. Reconciliation is an independent end-to-end check that
   the two books agree, so a bug, a manual database fix or a lost message shows up as a number rather than as a
   customer complaint. Money must be explainable.
2. **Why a one-day window, and "reported once, on the payment line's date"?** Model answer: the two services stamp
   their rows at slightly different moments, so around midnight a pair lands on two days. Same-day matching would
   raise a false MISSING_IN_WALLET on one day and MISSING_IN_PAYMENTS on the next. Looking at D-1 and D+1 finds the
   other half; reporting only on the payment line's date counts the pair once. A wider window would hide real gaps.
3. **Why only dates up to today minus 2?** Model answer: a payment finishing late on a day may have its hold settle
   after midnight, so the day after D must be over before D's statements are final. With D at most today minus 2, D+1
   is yesterday and already ended. The check runs before any download (`ReconciliationRun.java:108`).
4. **How does a crash halfway through the report not give duplicate or missing rows?** Model answer: the report
   primary key is `(business_date, payment_id)` and the insert ignores conflicts, so rows already written stay and
   missing ones are added on the restart. Completed Batch steps are skipped on restart; a completed instance is
   refused. A fresh run clears staging first so stale lines cannot leak into the report.
5. **Why does an unset `RECON_OPS_TOKEN` fail the start, and what was the earlier bug?** Model answer: a job without
   the token would only download 401 pages, so failing early is clearer. An unresolved placeholder is bound as literal
   text, which passes a not-blank check; giving it an empty default makes the check real.

## What breaks if...

Change `on conflict (business_date, payment_id) do nothing` in `JdbcReportStore.java:30` to a plain insert. Which tests
fail, what happens to a job that crashed in the middle of the report and is relaunched, and what does the operator
see? Then run `ReconJobRestartIntegrationTest` (`-Pit`, needs Docker) to check your prediction.

## TODO-ME

`reconciliation/src/test/java/com/siddheshfulawade/payflow/recon/domain/AdjacentDayEdgeTest.java`: write the body of
the `@Disabled` test (an id repeated on the adjacent days with different content must be refused). The comment in the
file says how to check it.
