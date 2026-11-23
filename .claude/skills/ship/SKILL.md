---
name: ship
description: Verify the current work branch, then hand it to the git-ops agent to push, open a PR to main, wait for the required CI checks and squash-merge on green.
argument-hint: "[optional PR title]"
---

Ship the current branch to `main`. Title override: $ARGUMENTS

1. **Branch**: must be `feature/*`, `fix/*`, `chore/*` or `docs/*`, never `main`. If the working tree isn't clean,
   commit the task's changes (Conventional Commits, task id in the subject).
2. **Gate**: `mvnw.cmd verify` (plus `-Pit` if the task touched integration tests, `-Psystem` for PF-09 and later
   chaos work). Fix failures before continuing.
3. **Checklist** (every task): SPEC.md task ticked, README Decisions entry, `docs/learn/PF-NN-*.md`, one TODO-ME
   stub, quiz questions in the handoff, ADR-0001 entry for any new dependency.
4. **Delegate** to the `git-ops` agent: task id, one-paragraph summary, and the SHA on which the gate passed.
5. **Result**: if git-ops reports a merge, update the handoff line and start the next task. If it reports red CI,
   fix the cause with the right agent and ship again. Stop only at a hard gate (CLAUDE.md).
