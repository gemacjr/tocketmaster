# 10 — Runbook & Testing

## Health & verification

```bash
curl -s localhost:18081/actuator/health   # per service, or via edge:
for s in event search booking queue; do curl -s localhost:18080/api/…; done
./infra/smoke.sh                          # golden path + conflict proof
```

## Operational tasks

| Task | Command |
|---|---|
| Rebuild search index | `make reindex` (or `POST /api/admin/search/reindex`) |
| Tune on-sale admission rate | `PUT /api/admin/queue/events/{id}/admission-rate {"ratePerSecond":100}` |
| Watch the sweeper | logs: `booking-service` `Swept N expired bookings` |
| Inspect holds/queues | `redis-cli`: `KEYS hold:*`, `ZRANGE queue:{eventId} 0 -1 WITHSCORES`, `ZRANGE reserved:{eventId} 0 -1 REV` |
| Force-release a stuck hold | delete `hold:{ticketId}` — Postgres claim still guards correctness |
| Reset a demo environment | `docker compose down -v && make infra-up && make run-all && make reindex` |

## Incident notes

- **"Some seats are no longer available" storms**: healthy contention behavior; scale
  booking-service and raise admission rate only if DB has headroom.
- **Bookings stuck PENDING**: webhook path broken — check booking-service logs for gateway
  self-call errors (`app.self-url`), confirm ALB/nginx route for `/api/payments/webhook`.
- **Search empty after admin create**: relay lag ≤ 2 s normally; if persistent, check outbox
  `processed_at` nulls and search-service `/internal/documents` reachability.
- **Redis flush during on-sale**: holds vanish → reservations fail closed; seats already claimed
  in Postgres are unaffected. Communicate, restart, users re-queue.

## Test matrix

| Layer | What | Where |
|---|---|---|
| Concurrency IT | 25 threads ⇒ 1 winner; TTL reclaim; OCC refund loser; idempotent webhook | `BookingConcurrencyIT` (Testcontainers) |
| Smoke | full user journey via edge incl. double-book 409 | `infra/smoke.sh` |
| Build gate | compile all modules + tests | `./gradlew build` |

Manual UI checklist: home grid renders seeded events → search fuzzy typo hit → detail seat map
shows tiers → reserve 2 seats → countdown appears → confirmation shows SOLD → re-reserving same
seats from another browser profile returns 409.
