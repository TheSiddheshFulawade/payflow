# 2026-11-27 · Session 1 · PF-07 PR red (pre-existing Hazelcast bug), fix branch WIP (parked: plan limit)

- **main `167d172`** (PR #8 squash-merge, the portfolio pin): CI run 45867171292 green 5/5.
- **PR #9 PF-07 `feat/pf-07-security`, OPEN, NOT merged.** Head `01e8ddd`: integration + 3 verify green; `verify (ubuntu,
  JDK 21)` red in risk-service `HazelcastMemberTest.joiningMemberIsHandedOutOnlyWhenClusterIsSafe` (run 45877124629).
  Cause is on main, not PF-07: the Hazelcast deserialization allow-list refused `PartitionStateVersionMismatchException`,
  so a join migration was never answered and the cluster stayed unsafe 30 s.
- **Fix branch `fix/hazelcast-filter-partition-errors` (from main), WIP pushed, no PR yet:** allow-list = 124 exact names
  from a jar scan (`risk-service/src/main/resources/hazelcast-allowed-errors.txt`) + `MemberVersion`; fail closed if the
  file is missing/empty; scan test; README Decision. Local risk-service verify green (122) BEFORE the last test edits.
- **Next:** (1) finish the security should-fixes in tests: pin the list exactly both ways (file == scan, whitelist ==
  explicit ∪ scan; test-engineer was stopped mid-edit, so re-check `HazelcastSerializationFilterTest`,
  `HazelcastAllowedErrorsFileTest`, `HazelcastFailureTest`) and real round trips (PartitionStateVersionMismatch,
  MemberLeftException, error with cause); document MemberLeftException's DNS lookup in readObject. (2) `-pl risk-service
  -am verify`, git-ops PR + merge on green. (3) git-ops merges main into PF-07 (no force), CI, merge #9 on green.
- **Cut line:** C6 → Keycloak container test → reconciliation 10,000 (smaller stated count) → Windows CI for system
  tests. PF-07 checkpoint (27 Nov) at risk; if it slips, cut C6.
- **Not checked:** the WIP test edits compile or pass; `-Pit` locally (never); Keycloak test passed in PR #9 integration.
- **Waiting on user:** licence (README says "public"); Docker Desktop for PF-08 to PF-10; delete old worktree
  `…/0532ea67-…/scratchpad/main-it` (clean, detached 2a1a723); confirm settlement-lab (ADR-0001) is your own project.
