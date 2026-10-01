# 02 — Architecture

```
                       ┌──────────────────────── CloudFront (SPA, /api/* → ALB) ─────────────┐
                       │                                                                     │
 Browser ── HTTPS ──►  ALB ── path rules ──►  event-service :8081   (Fargate, autoscaled)
                       │                          ├── Postgres (catalog)                      │
                       │                          ├── Redis (event cache, outbox relay)      │
                       │                          └──► search-service /internal/documents    │
                       │                                                                     │
                       ├─ /api/search ──►  search-service :8082  ── OpenSearch (VPC)         │
                       │                                  └── Redis result cache             │
                       │                                  └── Postgres ILIKE fallback        │
                       │                                                                     │
                       ├─ /api/bookings,/api/payments ►  booking-service :8083               │
                       │                          ├── Postgres (tickets/bookings/payments)   │
                       │                          ├── Redis (holds, reserved ZSET, admission)│
                       │                          └── Mock gateway ── webhook ──► itself     │
                       │                                                                     │
                       └─ /api/queue ────►  queue-service :8084  ── Redis ZSET + SSE         │

 Local: identical topology; nginx replaces ALB+CloudFront, docker-compose runs the stores,
 services run via `gradle bootRun` (or the `containers` compose profile).
```

## Request flows

**Read (catalog):** browser → edge cache headers (`Cache-Control: public,max-age=60`) → event-service
→ read-through Redis (`cache:event:{id}`, TTL 1h). Writes evict via the outbox relay.

**Read (search):** search-service normalizes query → SHA-256 key → Redis 60 s cache → OpenSearch
`multi_match` with fuzziness → on OpenSearch failure, Postgres ILIKE degraded results.

**Write (booking):** see `docs/05`. The short version: Redis holds (fast-fail contention) →
Postgres claim inside a transaction (correctness) → PENDING booking → async mock payment →
webhook finalizes with optimistic concurrency; losers auto-refund.

## Key decisions & trade-offs

| Decision | Why | Trade-off accepted |
|---|---|---|
| Monorepo, 4 deployables | independent scaling profiles (search is read-heavy, booking is correctness-heavy) without a distributed-monolith of 20 repos | shared build toolchain |
| Sync REST everywhere | simplest correct thing; SSE only where push matters (queue positions) | no gRPC/event streaming between services |
| Outbox table + poller | atomic "write + notify" without distributed transactions | ≤ `relay.poll-interval-ms` staleness |
| Mock Stripe as an HTTP peer with webhooks | exercises the real consistency problem (charge ≠ finalize atomically) | local-only simplification: gateway self-calls our own API |
| Postgres as single source of truth for inventory | one correctness boundary | DB write throughput is the ceiling — mitigated by holds pre-filtering and per-event row scoping |

## Failure modes (what breaks when X dies)

- **Redis down**: holds fail closed → reservations rejected (5xx); catalog/search serve from caches then DB; queue joins fail. No double-sell possible.
- **OpenSearch down**: search falls back to Postgres ILIKE automatically (degraded relevance).
- **One service instance down**: ALB health checks drain it; stateless peers pick up.
- **Payment gateway down**: bookings stay PENDING until hold TTL expiry releases seats; sweeper cancels them. Money never moves without a webhook.
