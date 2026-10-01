# TocketMaster

A Ticketmaster-style ticketing platform built to demonstrate the architecture from the
[Hello Interview Ticketmaster system design breakdown](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster)
as working code: **React SPA → nginx edge → four Spring Boot services → PostgreSQL / Redis / OpenSearch**,
deployable locally with Docker Compose or to AWS with Terraform (ECS Fargate).

## What it implements

| Concern | Mechanism |
|---|---|
| Seat holds | Redis `SET NX PX` all-or-nothing holds, 10 min TTL (`docs/05`) |
| No double-sell | Postgres optimistic concurrency: claim rows only `WHERE status='AVAILABLE'`, rowcount decides winner; loser auto-refunds |
| Payment consistency | Mock Stripe gateway → idempotent webhook (`payment.succeeded`) finalizes booking |
| Virtual waiting room | Redis ZSET FIFO queue + admission worker (N/sec) + SSE position stream (`docs/06`) |
| Search | OpenSearch fuzzy multi_match w/ Redis result cache + Postgres degraded fallback (`docs/07`) |
| Read scaling | CDN-style cache headers, read-through event cache, outbox relay invalidation |

## Quickstart

```bash
make infra-up        # postgres + redis + opensearch + nginx (seeded DB)
make run-all         # 4 services via gradle bootRun (background, logs in .run/)
make reindex         # seed OpenSearch from Postgres
open http://localhost:18080          # UI
./infra/smoke.sh     # end-to-end API proof: search -> hold -> pay -> SOLD, then 409 on double-book
```

Stop everything: `make down-all`. Full command reference: `docs/09`.

## Services

| Service | Local port | Responsibility |
|---|---|---|
| event-service | 18081 | Catalog reads, seat maps, admin CRUD, cache/outbox |
| search-service | 18082 | Query path, OpenSearch indexing/reindex |
| booking-service | 18083 | Holds, reservations, payments, webhook, sweeper |
| queue-service | 18084 | Waiting room join/status/SSE/admission |

Edge/nginx serves UI + `/api/*` on **http://localhost:18080** (container-internal ports remain 8081–8084).

Routing rules live in `infra/nginx/default.conf.template` and mirror the ALB listener rules in `infra/terraform`.

## Docs

1. [Requirements](docs/01-requirements.md)
2. [Architecture](docs/02-architecture.md)
3. [API reference](docs/03-api.md)
4. [Data model](docs/04-data-model.md)
5. [Ticketing deep dive — holds, OCC, payments](docs/05-ticketing-deep-dive.md)
6. [Virtual waiting room](docs/06-waiting-room.md)
7. [Search pipeline](docs/07-search.md)
8. [Scaling & AWS deployment](docs/08-scaling-aws.md)
9. [Local development](docs/09-local-dev.md)
10. [Runbook & testing](docs/10-runbook.md)

## Repo layout

```
common/                  shared primitives (HoldManager, ReservedSeatIndex, exceptions)
services/event-service   catalog + caching + admin + outbox relay      :18081
services/search-service  search query + indexing                      :18082
services/booking-service reservations, OCC finalize, mock gateway    :18083
services/queue-service   waiting room + admission worker              :18084
frontend/                React SPA (Vite)
infra/docker-compose.yml local stack (pg16 / redis7 / opensearch / nginx)
infra/terraform/         AWS: VPC, RDS, ElastiCache, OpenSearch, ALB, ECS Fargate, CloudFront
infra/smoke.sh           end-to-end correctness proof
docs/                    design documents
```

Requires JDK 17 (Corretto), Docker Desktop, Node ≥ 20.
