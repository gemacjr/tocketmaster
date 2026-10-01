# 09 — Local Development

Prereqs: JDK 17 (`/usr/libexec/java_home -v 17`), Docker Desktop, Node ≥ 20.

## Full stack

```bash
make infra-up     # docker compose up -d: postgres(:15432) redis(:6379) opensearch(:9200)
                  #   nginx(:8080) + frontend build served by nginx
make run-all      # gradle bootRun ×4 (event 18081, search 18082, booking 18083, queue 18084)
make reindex      # seed OpenSearch index from Postgres
open http://localhost:18080
```

`make logs`, per-service `make run-event|run-search|run-booking|run-queue`, teardown
`make infra-down` / `make down-all`.

## All-in-Docker variant

```bash
docker compose --profile containers up --build   # adds the 4 service images
```

Requires images to be built via `infra/docker/service.Dockerfile`
(`--build-arg MODULE=<service-name>`); the frontend container runs `npm ci && npm run build`,
so `frontend/package-lock.json` must exist (it does — committed).

## Seed data

`infra/postgres/init.sql` auto-loads on first volume init:

- 2 venues (Ember Dome Austin 1480 seats · Grand Hall NYC 328 seats), 6 performers,
- 6 events `eeeeeee1-0000-0000-0000-00000000000{1..6}`, tier-priced tickets materialized,
  event **1** has the waiting room enabled.

Reset everything: `docker compose down -v` then `make infra-up`.

## Tests

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew build            # unit + BookingConcurrencyIT (Testcontainers: PG16 + Redis7)
./gradlew :services:booking-service:test --tests '*BookingConcurrencyIT*'
```

The IT proves under real Docker: 25-thread reserve contention has exactly one winner;
expired holds are re-claimable; OCC losers get refunded; duplicate webhooks are idempotent.
Docker Desktop must be running.

## End-to-end smoke

```bash
./infra/smoke.sh   # search → detail → hold 2 seats → confirm → SOLD → double-book ⇒ 409
```

## Frontend dev mode

```bash
make frontend-dev  # vite on :5173, proxies /api → nginx :18080
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Unsupported class file major version` | JAVA_HOME not 17 |
| OpenSearch yellow/red | single-node dev is yellow by default; check `make logs-opensearch` |
| 403 WAITING_ROOM_REQUIRED on event …0001 | expected — join queue first or use another seed event |
| Webhook connection refused | booking-service `app.self-url` must point at a reachable edge (:8080 local / ALB in AWS) |
