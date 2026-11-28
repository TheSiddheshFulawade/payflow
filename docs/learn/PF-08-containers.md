# PF-08 Containers: run the whole stack with one command

Status: honest. Nothing has been built or run in Docker yet, and the base image tag
(`eclipse-temurin:21.0.9_10-jre-noble`) is unverified: it is not pulled until the first `docker build`. Tests run
locally: system-tests 48 run, 0 failures, targeted service tests green. CI has not run this branch yet. Line numbers
are from the working tree and may move.

## What the code does, in plain words

1. **A Dockerfile per service** (`payment-service/Dockerfile`; wallet and risk are the same shape). The jar is built on
   your machine first; no Maven runs inside Docker (`Dockerfile:1`).
2. **`deploy/compose.yaml`** starts Postgres, Kafka, Keycloak, payment, wallet and two risk members, in dependency
   order, each waiting for the others' healthcheck (`compose.yaml:100-106`).
3. **`deploy/demo.sh` / `demo.ps1`** get tokens, seed two wallets, send four payments and print their end states.
4. **The system test** starts the same compose file under its own project name.

## Core concepts

**Layered Boot jar (`Dockerfile:5-16`).** A Boot jar is one big zip, but most of it is libraries that rarely change.
`-Djarmode=tools extract --layers` splits it into `dependencies`, `spring-boot-loader`, `snapshot-dependencies` and
`application`. Docker caches each `COPY` as a layer, and a changed layer invalidates every layer after it. So the order
is least-changing first and `application` (your code) last: a code change rebuilds only a small layer. Put
`application` first and every build would re-copy all the libraries.

**Non-root numeric user (`Dockerfile:17-19`).** `USER 10001:10001`. If the app is hijacked it is not root in the
container. A number works even if `/etc/passwd` has no such name, and policy checks such as "must run as non-root"
can verify a number. Files stay root-owned, so the process can read but not rewrite its own code.

**`MaxRAMPercentage` vs `mem_limit` (`Dockerfile:21`, `compose.yaml:82`).** `mem_limit: 768m` is the wall: exceed it and
the kernel kills the container. `-XX:MaxRAMPercentage=70` makes the heap about 70 % of that wall (the JVM reads the
container limit), leaving room for metaspace, thread stacks and buffers. A fixed `-Xmx` would ignore a changed limit.

**Issuer vs jwk-set-uri (`compose.yaml:63-65, 96-97`; `application.yml:39-43`).** Two different questions about a token:
"who claims to have issued it?" (the `iss` string inside) and "where do I download the keys to check the signature?".
Keycloak is told `KC_HOSTNAME=http://localhost:8180`, so every token says `iss=http://localhost:8180/realms/payflow`
whoever asked. But inside a container `localhost` is the container itself, so a service cannot fetch keys there. So
`PAYFLOW_ISSUER_URI` stays the localhost string (compared with the claim) and `PAYFLOW_JWK_SET_URI` points at
`keycloak:8080` (fetched over the private network). The `iss` check stays: without it a token from any other server
with a valid signature of its own would pass. `SecurityConfig.java:82-90` also makes a blank issuer fail the start:
Boot adds its issuer check only when the issuer has text, so blank plus a jwk-set-uri would silently accept any issuer.

**Internal risk-cluster network (`compose.yaml:173-183, 247-254`).** The Hazelcast port (5701) has no authentication, so
only the two risk members share a network with `internal: true` (no route out), a pinned subnet, and Hazelcast binds
only `172.28.77.*` (`RISK_HAZELCAST_BIND`). The port is in no `ports:` list, so the host cannot reach it. `extra_hosts`
pins the other member's name to its cluster address, because Docker DNS could answer with the default-network address.

**Hardening (`compose.yaml:83-90`).** `read_only: true` makes the root filesystem unwritable (only the `/tmp` tmpfs is
writable, which Boot and Tomcat need); `cap_drop: ALL` removes Linux privileges the app never uses;
`no-new-privileges` stops a process gaining more through setuid binaries. Each shrinks what an attacker can do after a
breach. Published ports all bind `127.0.0.1`.

**Healthchecks without curl (`compose.yaml:107-113`).** The JRE image has no curl or wget. Bash can open a TCP socket
itself, send `GET /actuator/health` and read the status line; `$$` stops compose expanding `${status}` itself.
Keycloak's check does the same on management port 9000 (`compose.yaml:70-76`).

## Demo flow (`deploy/demo.sh:66-83`)

Token for `alice` and `ops`, then ops seeds a payer wallet (6,000,000) and a payee (5,000), then four payments each
with an `Idempotency-Key`, then poll each until COMPLETED, REJECTED or REVERSED (120 s limit). Expected:

| name | amount | outcome | why |
|---|---|---|---|
| ok-1 | 2500 | COMPLETED | funds and limit fine |
| ok-2 | 1000 | COMPLETED | same |
| risk-limit | 5000001 | REVERSED | funds held, then over the 5,000,000 limit, hold released |
| insufficient-funds | 9000000 | REJECTED | more than the payer has left, nothing held |

```
demo    -> keycloak: token(alice), token(ops)
demo    -> wallet:   seed payer, payee
demo    -> payment:  POST /payments (Idempotency-Key)  -> id
payment -> wallet:   hold funds (Kafka)   -> held / refused
payment -> risk:     check limits (Kafka) -> approved / refused
payment -> wallet:   commit or release    -> COMPLETED / REVERSED  (REJECTED when nothing was held)
demo    -> payment:  GET /payments/{id} until terminal
```

## The system test's own project (`ComposeHappyPathSystemTest.java:40, 58, 63`)

It runs `docker compose -p payflow-systemtest ... up --build --wait` and `down -v`. A separate project name means its
containers, network and volumes are separate from your demo stack, so `down -v` cannot delete your demo data.
`ComposeProjectIsolationTest` fails if the `-p` is removed.

## Interview questions

1. **Why does layer order matter?** A changed layer invalidates every later one. Stable libraries first, your code last,
   so a code change re-copies a small layer, not the whole dependency set.
2. **Why a numeric non-root user?** A compromised process is not root; a number needs no `/etc/passwd` entry and can be
   verified by policy checks.
3. **Why `MaxRAMPercentage` and not `-Xmx`?** The heap follows the container limit, so changing `mem_limit` needs no
   second edit; the other 30 % covers non-heap memory so the kernel does not kill the container.
4. **Tokens say localhost but the service is in a container. How does validation work?** Issuer (a compared string) and
   key location (a fetched URL) are separate settings. Keep the `iss` check, fetch keys from `keycloak:8080`. A blank
   issuer fails the start so the check cannot be silently skipped.
5. **Why is the Hazelcast port never published?** It has no authentication. Only the two members share an `internal`
   network, so neither the host nor any other container can reach it.

## What breaks if...

...you delete `extra_hosts` from `risk-1` and `risk-2`? Trace it: Docker DNS may answer `risk-2` with its
default-network address, Hazelcast binds only `172.28.77.*`, so what happens to the join and what would the cluster
look like? (Read `compose.yaml:173` and `181-183`.)

## TODO-ME

In `system-tests/.../DemoScriptTest.java`, the `@Disabled` test `scriptsSendTheFourDemoPayments`: assert both scripts
name the four payments (`ok-1`, `ok-2`, `risk-limit`, `insufficient-funds`) with amounts 2500, 1000, 5000001, 9000000.
Check: remove `@Disabled`, run `DemoScriptTest`; then change one amount in `demo.sh` and see it fail.
