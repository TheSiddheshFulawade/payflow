# PayFlow

Crash-safe payment orchestration: three Spring Boot microservices (payment, wallet, risk) over Kafka with an
orchestrated saga, transactional outbox, idempotent consumers, Hazelcast velocity limits, Keycloak JWT security and
a Spring Batch reconciliation job, proven by chaos tests. Owner: Siddhesh Fulawade (public repo, original work).

**SPEC.md is the source of truth** (requirements, design, tasks). Read it before any work. Decisions: `docs/adr/`.

## Rules

- **Original work only.** Nothing from any employer: no designs, names, code, message formats, system names or
  volumes. No ISO 20022 or RTGS/NEFT naming. Generic terms only (wallet, payment, hold, risk, statement).
- **Java 21** language level, built on JDK 21 and 25. Money is `long` minor units, never `double`.
- **Tests first** for every task (test-engineer → implementer). Awaitility for async; never `Thread.sleep` to
  synchronise. JaCoCo ≥ 80 % per service module.
- **Simple, readable code**: records, small classes, the invariant written next to the code that protects it.
- No new dependency without an entry in `docs/adr/0001-dependencies.md`.
- Never read or write secrets (`.env*`, keys, certificates). Keycloak admin and client secrets in compose are
  local demo values in `deploy/`, clearly marked, never reused anywhere real.
- Never use `--no-verify`, force push, or push to `main`.

## Autonomy (long unattended stretches)

The main session is the **PayFlow master agent** (project manager). It plans, decides routine technical questions,
delegates to subagents (they cannot start other subagents), and keeps going task after task without waiting.
**Merges are delegated to `git-ops`**, which merges only when GitHub's required checks are green.

Hard gates (stop, record under "Waiting on user" in the handoff, move to the next unblocked task):
money or cloud resources, secrets, GitHub repo settings, anything from an employer, a product choice no approved
spec covers, changing SPEC.md scope (§2) or the cut line (§8).

## Task loop

`test-engineer` (failing tests) → `java-engineer` (or `messaging-engineer` for Kafka/outbox/saga work) →
`mvnw.cmd verify` (plus `-Pit` for Testcontainers tests, `-Psystem` for chaos) → `security-auditor` when security,
pom files or CI change → `tutor` (learn note + README Decision + TODO-ME) → `git-ops` (PR, CI, merge) → handoff line.

## Defend every line

- **README "Decisions"** entry per task: what was chosen, the alternative, and why.
- **Learn notes** `docs/learn/PF-NN-topic.md`: plain words, the concept, 5 interview questions with model answers.
- **One TODO-ME per task**: a small real piece (a test, a method, an edge case) marked `// TODO-ME:` with what to do
  and how to check it. Never fill it in; it never blocks the next task.
- Each task's 5 quiz questions go into the handoff; the user answers them with `/quiz`.

## Commands

- Gate: `mvnw.cmd verify` (Windows) or `./mvnw verify`. Integration: `-Pit`. Chaos/system: `-Psystem` (needs Docker).
- Stack: `docker compose -f deploy/compose.yaml up -d`; demo: `deploy/demo.ps1`.
- Hook tests: `node --test .claude/hooks/*.test.mjs`.

## Claude setup

- Hooks: `session-start` (SPEC task status + latest handoff), `guard-bash` and `protect-secrets` (safety; pattern
  rules are a safety net, not a sandbox).
- Skills: `java-standards`, `messaging-patterns`, `testing-playbook` (load before code or tests), `/ship`,
  `/handoff`, `/quiz [task]`.
- Agents: `test-engineer`, `java-engineer`, `messaging-engineer`, `security-auditor`, `tutor`, `git-ops`.

## Handoffs

After each merged task, update `docs/sessions/YYYY-MM-DD-NN-topic.md` (at most 20 lines): done, state, next,
waiting on user, quiz.
