# 01 — Requirements

Source: [Hello Interview — Ticketmaster](https://www.hellointerview.com/learn/system-design/problem-breakdowns/ticketmaster)

## Functional

1. **View events** — browse/search events by name, performer, venue, city; view event detail with a seat map and per-tier pricing.
2. **Reserve tickets** — user selects seats (or best-available), places them "on hold" for a limited window (10 minutes here) while paying.
3. **Purchase** — pay for held seats within the hold window; receive a booking confirmation. Payment failures release the hold.
4. **Fairness under extreme demand** — hot on-sales must not let the fastest click win everything silently or crash the system; queue demand before it reaches inventory (virtual waiting room).
5. **Admin** — create venues/events, reindex search, tune admission rate.

Out of scope (documented, not built): real PSP integration, refunds workflow UI, transfers/resale, fraud/velocity rules, authn (we trust `X-User-Id`; in prod: OIDC + session).

## Non-functional

| Requirement | Target | How we meet it |
|---|---|---|
| Correctness: **never sell a seat twice**, even across concurrent processes | hard invariant | Postgres OCC claim (`docs/05`); Redis holds are only an availability layer |
| Availability | degrade, don't fail | Search falls back to Postgres; waiting room absorbs spikes; holds expiring is safe |
| Read latency | p99 < 100 ms catalog/search | CDN headers, read-through Redis caches, 60 s search-result cache |
| Consistency of reads | eventual ≤ 1 s after writes | outbox relay evicts caches + upserts OpenSearch |
| Scale target (from guide) | 50M tickets/yr, peak ~10k RPS reads / ~2k writes during on-sales, single event = ~20k seats | stateless services scale horizontally; all contention funnels to Postgres row-level locks + Redis |
| Auditability | every booking/payment transition persisted | `bookings`, `payments`, `outbox` tables; CAS status transitions |

## The core tension

The guide's central insight: **inventory correctness must live in one place**. We put it in
Postgres row-level claims (a single `UPDATE ... WHERE status='AVAILABLE'` per ticket), and use
Redis only to make the *experience* good — instant seat holds that expire, and admission
throttling. Every component is allowed to be wrong or disappear; Postgres decides who wins.
