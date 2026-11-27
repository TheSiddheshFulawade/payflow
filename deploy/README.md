# Local stack

```sh
./mvnw -DskipTests package        # the Dockerfiles copy target/*.jar; no Maven runs inside Docker
docker compose -f deploy/compose.yaml up -d --build --wait
```

| Service        | Host address       |
|----------------|--------------------|
| Postgres       | `127.0.0.1:5432`   |
| Kafka          | `127.0.0.1:9092`   |
| Keycloak       | `127.0.0.1:8180`   |
| payment-service| `127.0.0.1:8081`   |
| wallet-service | `127.0.0.1:8082`   |
| risk-1         | `127.0.0.1:8083`   |
| risk-2         | `127.0.0.1:8084`   |

- Postgres creates databases `payment`, `wallet`, `risk`, `recon` on first start (`postgres/init`).
- Stop with `docker compose -f deploy/compose.yaml down` (add `-v` to wipe the Postgres volume).
- Every password here is a LOCAL DEMO VALUE; never reuse it anywhere real. Ports bind to localhost only.

## Demo

With the stack up (`--wait` returns when every healthcheck passes):

```sh
./deploy/demo.sh      # Linux, macOS, Git Bash (needs curl)
./deploy/demo.ps1     # Windows PowerShell
```

Both do the same: get tokens for `alice` and `ops` from Keycloak, seed a payer and a payee wallet through the ops API,
send payments with an `Idempotency-Key`, poll each until `COMPLETED`, `REJECTED` or `REVERSED` (or a timeout), and
print the states. The wallets get fresh random ids on every run.

## Memory budget

Each container has a `mem_limit`; the sum is 5.5 GB: postgres 512 MB, kafka 1 GB, keycloak 1 GB, payment, wallet,
risk-1 and risk-2 768 MB each. The services use `-XX:MaxRAMPercentage=70`, so the heap is 70 % of the limit.
Docker Desktop must be allowed at least 6 GB.

## Design notes

- **Issuer and keys.** Keycloak runs with `KC_HOSTNAME=http://localhost:8180`, so a token fetched from the host carries
  `iss` = `http://localhost:8180/realms/payflow`. The services expect that issuer (`PAYFLOW_ISSUER_URI`) but fetch keys
  from `http://keycloak:8080/...` (`PAYFLOW_JWK_SET_URI`), because `localhost` inside a container is the container.
- **Healthchecks.** The JRE and Keycloak images have no curl or wget, so each check is `bash` opening a TCP socket
  (`/dev/tcp`), sending `GET /actuator/health` (Keycloak: `/health/ready` on port 9000) and testing for a `200`.
- **Hazelcast.** `risk-1` and `risk-2` form a cluster on the `risk-cluster` network (`internal`, fixed subnet
  `172.28.77.0/24`, no route out). Port 5701 is never published, and Hazelcast binds only to that subnet. The members
  are pinned in `extra_hosts` to their addresses there, so Docker DNS cannot hand out the default-network address.
  Change the subnet in `compose.yaml` (network, `ipv4_address`, `extra_hosts`, `RISK_HAZELCAST_BIND`) if it clashes.
