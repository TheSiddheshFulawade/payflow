# PF-01 · The build skeleton

You know Spring Boot, Kafka and Oracle. PF-01 has no business logic. It builds the floor the rest stands on:
one build for seven modules, rules that fail the build when someone cuts a corner, a CI pipeline, and a local
stack (PostgreSQL, Kafka, Keycloak). Every line below points at the file that does it.

## 1. What the code does, in plain words

- **One build, seven modules.** The root `pom.xml` (lines 14-22) lists `contracts`, `messaging`, three services,
  `reconciliation` and `system-tests`. `./mvnw verify` builds them all.
- **The Boot BOM is imported, not inherited.** A BOM ("bill of materials") is a pom that only holds a list of
  matching library versions. `pom.xml:68-74` imports `spring-boot-dependencies` with `<scope>import</scope>`. You
  get Boot's tested versions of Jackson, Spring Kafka, Flyway and so on without making `spring-boot-starter-parent`
  your parent. Why it matters: a Maven project has exactly one parent, and ours holds the rules every module
  shares (enforcer, Spotless, JaCoCo, test tags). Libraries like `contracts` should not be "Boot apps" either.
- **Maven wrapper.** `.mvn/wrapper/maven-wrapper.properties` pins Maven 3.10.1 and its SHA-256. `./mvnw` downloads
  that exact Maven, so a laptop, Windows CI and Ubuntu CI all use the same one. Type `only-script` means no
  binary jar is checked in, only the `mvnw` scripts.
- **Enforcer** (`pom.xml:200-230`) fails the build when:
  - `dependencyConvergence`: two libraries pull two different versions of the same third library. Without it,
    Maven silently picks one and you find out in production.
  - `requirePluginVersions`: a plugin has no pinned version (a build that changes by itself).
  - `bannedDependencies`: `com.hazelcast:hazelcast-enterprise`, which is not open source (ADR-0001 line 12).
  - Java at least 21 and Maven at least 3.9.
- **Test tags and profiles** (`pom.xml:33-38`, `151`, `308-322`). Surefire (the unit-test runner) excludes tests
  tagged `it` and `system` by default. `-Pit` sets the "excluded it" property to `none`, a tag no test uses, so
  nothing is excluded for `it`. Why replace instead of removing: the exclude list is `${tag.it.excluded},${tag.system.excluded}`,
  and a property cannot be "deleted", only set to something harmless. Because each profile touches only its own
  property, `-Pit,system` runs both. Docker is needed for both and a missing Docker fails, it does not skip.
- **Spotless** (`pom.xml:232-255`) checks formatting (palantir-java-format) at `verify`. Unformatted code fails
  the build; `./mvnw spotless:apply` fixes it. Arguments about style never reach code review.
- **JaCoCo** (`pom.xml:257-304`) measures which lines tests run and fails below 80 % per module
  (`coverage.line.minimum`, line 31). It excludes `**/*Application.class`: a Boot entry point's `main()` only
  calls `SpringApplication.run`, and the context tests already prove each app starts. Counting that one line would
  drag a tiny module under 80 % for no safety gain.
- **ArchUnit** tests (for example `payment-service/.../payment/ArchitectureTest.java`) are ordinary unit tests
  that read compiled classes and check package rules:
  - `domain` imports only `java..`, itself and `contracts` (lines 31-39), and no Spring (41-48).
  - `domain` never depends on `infra`, `api` or `app` (50-57); `api` never touches `infra` (59-66).
  - A service never imports `wallet`, `risk` or `recon` classes (68-79): services talk through messages only.
  - No package cycles (81-83).
  - `allowEmptyShould(true)` lets a rule pass while its package has no classes yet (the packages arrive task by
    task). The class comment (lines 16-19) says to remove it when the package gets its first class, otherwise a
    rename could turn the rule off silently.
- **CI** (`.github/workflows/ci.yml`):
  - `verify` job (lines 19-54): matrix of Ubuntu 24.04 and Windows 2025 times JDK 21 and 25, unit tests only, no
    Docker. 21 proves code stays compatible with `release 21`; 25 is the newest runtime. `fail-fast: false` so one
    red cell does not hide the others.
  - `integration` job (56-92): Ubuntu with JDK 25 only, because Linux Docker is what Testcontainers needs. It
    runs `verify -Pit`.
  - Actions are pinned to a commit SHA with the tag in a comment (lines 34, 37). A tag can be moved by whoever
    owns the action; a SHA cannot.
  - `permissions: contents: read` (lines 9-10): the job token can only read the repo. If a dependency or action
    is compromised, it cannot push or edit releases.
  - `persist-credentials: false` (lines 36, 64): checkout does not leave the token in `.git/config` for later
    steps to find.
  - Job names (line 22) are stable on purpose: they are the "required checks" on `main`.
  - Downloads are retried 3 times; tests are never retried (a retried flaky test hides a real bug).
- **Compose** (`deploy/compose.yaml`):
  - **PostgreSQL**: one server, but `deploy/postgres/init/01-databases.sql` creates four databases
    (`payment`, `wallet`, `risk`, `recon`), each with its own owner login (lines 4-12). Then `REVOKE CONNECT ... FROM
    PUBLIC` (lines 15-18). In PostgreSQL every role is in the pseudo-role `PUBLIC`, and `PUBLIC` may connect to
    every database by default. Without the revoke, the `wallet` user could open the `payment` database. With it,
    each login reaches only its own. (Oracle comparison: think four schemas with four users and no grants between
    them, except here it is the whole database that is closed off.)
  - **Kafka**: image `apache/kafka:4.4.0`, KRaft mode (Kafka's own built-in controller replaces ZooKeeper), one
    process acting as broker and controller (`KAFKA_PROCESS_ROLES: broker,controller`, line 25). Two listeners
    for clients (lines 27-29): `PLAINTEXT` on `kafka:29092` for other containers, `EXTERNAL` on
    `localhost:9092` for your host. A client connects, then the broker tells it the "advertised" address to use
    from then on; that address must be reachable from where the client lives, hence two. Replication factors are
    1 because there is one broker.
  - **Keycloak**: `start-dev --import-realm` (lines 50) loads `deploy/keycloak/payflow-realm.json`: realm
    `payflow`, roles `payments:write`, `payments:read`, `ops` (json lines 6-18), demo users alice, bob and ops,
    and a **public** client `payflow-test` (lines 19-28). Public means no client secret; with
    `directAccessGrantsEnabled` a test can post a username and password and get a token. `standardFlowEnabled:
    false` turns off the browser login flow nobody needs here. All passwords are marked LOCAL DEMO VALUES.
  - All ports bind to `127.0.0.1`, so nothing is exposed to the network.

## 2. Core concept: make the rules executable

A wiki page saying "services must not import each other" is forgotten in a month. A failing build is not. PF-01
turns each team agreement into a check: versions (enforcer), format (Spotless), coverage (JaCoCo), structure
(ArchUnit), supply chain (pinned SHAs, least-privilege token). Later tasks only add code; the floor holds.

## 3. Flow: what happens on a pull request

```
Developer        GitHub            verify x4 cells              integration
   | push PR        |                     |                          |
   |--------------->| start both jobs     |                          |
   |                |-------------------->| checkout (no creds kept) |
   |                |                     | setup-java 21 or 25      |
   |                |                     | mvnw go-offline (retry)  |
   |                |                     | mvnw verify:             |
   |                |                     |  enforcer, compile -Werror
   |                |                     |  surefire (it/system off)|
   |                |                     |  ArchUnit, Spotless, JaCoCo
   |                |------------------------------------------------>| pull images (retry)
   |                |                     |                          | mvnw verify -Pit
   |<---------------| 5 checks green -> merge allowed on main        |
```

## 4. Interview questions

1. **Why import the Spring Boot BOM instead of using `spring-boot-starter-parent`?**
   A project has one parent. We want ours to carry shared build rules for both libraries and services. Importing
   the BOM (`pom.xml:68-74`) gives the same tested version set. What we give up: the parent also pre-configures
   plugins (repackage, resources filtering); we configure the Boot plugin ourselves (`pom.xml:184-195`).

2. **What does `dependencyConvergence` protect you from?**
   Two dependencies requesting different versions of one transitive library. Maven's "nearest wins" rule picks one
   silently and a method may be missing at runtime. The rule fails the build, so a human chooses the version
   (usually in dependencyManagement).

3. **How do `-Pit` and `-Psystem` switch test groups on, and why `none`?**
   Surefire excludes `${tag.it.excluded},${tag.system.excluded}`. Each profile overrides its own property with
   `none`, a tag nobody uses, so that group is no longer excluded. Separate properties mean the two profiles
   combine. A missing Docker makes those tests fail, not skip, so a green run really ran them.

4. **Why `persist-credentials: false`, SHA-pinned actions and `contents: read`?**
   They limit what a compromised step can do. Pinned SHAs stop a moved tag from injecting new code; read-only
   token means it cannot push or tamper with releases; not persisting credentials keeps the token out of
   `.git/config` where later steps (or scripts from dependencies) could read it.

5. **Why four databases with `REVOKE CONNECT ... FROM PUBLIC` rather than four schemas, or four servers?**
   Services must not read each other's tables, which is the same boundary as in a real deployment. Separate
   databases plus owners give that isolation; `PUBLIC` can connect by default, so the revoke is what makes it
   true. Four servers give the same isolation for more RAM, which a laptop demo does not need. In production you
   would still choose separate servers where failure or load isolation matters.

## 5. What breaks if... (exercise)

What breaks if you delete the four `REVOKE CONNECT ... FROM PUBLIC` lines in `01-databases.sql`?

Think first, then check. Hint: log in as `wallet` and try to connect to the `payment` database. Nothing in the
build fails, and every test still passes, so what in this repo would (or would not) notice? Then ask where
you would add a test (the `DeployConfigTest` mentioned in `ci.yml:80` already checks compose) and what it would
assert.

## TODO-ME for this task

In `payment-service/.../payment/ArchitectureTest.java` there is a commented stub for the rule
`moneyIsNeverFloatingPoint`. Write it, then prove it works: add a `double` field to a class in `..domain..`, see
the rule fail, remove it. (Careful: the package is empty today, so you also need to decide how `allowEmptyShould`
should behave for this rule, and why.)
