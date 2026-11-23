# Local stack

```sh
docker compose -f deploy/compose.yaml up -d
```

| Service  | Host address          |
|----------|-----------------------|
| Postgres | `127.0.0.1:5432`      |
| Kafka    | `127.0.0.1:9092`      |
| Keycloak | `127.0.0.1:8180`      |

- Postgres creates databases `payment`, `wallet`, `risk`, `recon` on first start (`postgres/init`).
- Stop with `docker compose -f deploy/compose.yaml down` (add `-v` to wipe the Postgres volume).
- Every password here is a LOCAL DEMO VALUE; never reuse it anywhere real. Ports bind to localhost only.
