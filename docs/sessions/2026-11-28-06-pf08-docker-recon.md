# 2026-11-28 · Session 6 · PF-10 and PF-08 merged, measured run done, PF-09 on Docker

- **Merged:** #14 PF-10 (`2f2eda1`, main CI 46133198893 green); #12 PF-08 (`d8ff030`, 5/5 green on `b68b54f`).
- **PF-08 proven on Docker Desktop 28.1.1:** compose build, `up -d --wait` 7/7 healthy (~2.4 GB), demo.ps1 run by the
  user → 2 COMPLETED, 1 REVERSED, 1 REJECTED. Bug found and fixed test-first: risk-1 and risk-2 both built the same
  image tag and raced ("already exists"); risk-2 now reuses it with `pull_policy: never` (`eachImageIsBuiltOnce`).
- **PF-10 measured run (REQ-PF-042):** 100,000 seeded payments reconciled in 19.3 s job / 22.6 s wall, all MATCHED;
  10,000 in 2.4 s. Rows seeded (closed-date rule), conditions in `docs/results/PF-10-measured-run.md`; repeat with
  `deploy/recon-measured/run.ps1`. No cut needed (full 100,000).
- **PF-09** `feat/pf-09-system` (local, main merged in): EndToEnd + Chaos C1-C5 system tests, SystemStack/Api/
  Scenario/SafeIds helpers, CI `system` job; audit READY, 2 SHOULD-FIX fixed. `-Psystem` local: 94/0, all invariants
  held (C1 49 s, C2 11 s, C3 50 s, C4 51 s, C5 116 s; one run each). C6 cut (SPEC §8).
- **Lessons:** `-ExecutionPolicy Bypass` is denied, use `-File` only; guard-bash blocks secrets in Bash env vars, so
  token-using steps go in a reviewed .ps1; `!` commands run in Git Bash (use forward slashes); stacked branch after a
  squash merge → take main's side for files the branch never changed.
- **Not checked:** image digests not pinned; demo.sh not run; CI `system` job's first run is in the PF-09 PR.
- **Next:** PF-11 README (diagrams, results), PF-12 release v1.0.0. **Waiting on user:** nothing.
- **Quiz PF-08:** layered jars; non-root + read-only fs; two issuer URIs; one risk image for two members; build race.
- **Quiz PF-09:** system vs Testcontainers test; why `down -v` spares the demo stack; mechanism per C1-C5; the six
  invariants (double charge? duplicate?); why no MATCHED check in chaos runs.
