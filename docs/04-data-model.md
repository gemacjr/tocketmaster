# 04 — Data Model

## PostgreSQL (`infra/postgres/init.sql` is canonical)

```
venues(id, name, city, seat_map jsonb)          -- seat_map: tiers with rows/counts/price
performers(id, name, category)
events(id, venue_id, performer_id, name, description, starts_at,
       status, queue_enabled, created_at, updated_at)
tickets(                                        -- one row per physical seat (pre-materialized)
  id, event_id →events, tier, row_label, seat_number, price_cents,
  status        AVAILABLE | HELD | SOLD,
  booking_id    nullable →bookings,
  UNIQUE(event_id, row_label, seat_number))
bookings(id, user_id, event_id, status, total_cents, expires_at, created_at, updated_at)
  -- status: CREATED→PENDING→CONFIRMED | EXPIRED | CANCELLED | REFUNDED
payments(id, booking_id, provider_ref UNIQUE, amount_cents, state, idempotency_key, …)
outbox(id, aggregate_type, aggregate_id, type, payload jsonb, created_at, processed_at)
```

Indexes that carry the hot paths:
- `tickets(event_id, status)` — seat map reads + claim queries
- `bookings(user_id)`, `payments(provider_ref)` (idempotency lookup)
- `outbox(processed_at)` partial index — relay drain

**Why a ticket row per seat?** Inventory correctness needs exactly one claimable unit per seat.
20k rows per big show is trivial for Postgres; the alternative (counters/buckets) can't express
"these specific seats".

**Status machines are enforced by CAS updates**, never read-modify-write in app code:
```sql
UPDATE bookings SET status='EXPIRED' WHERE id=? AND status='PENDING'
-- finalization: claim + flip in one guarded statement (rowcount = truth)
UPDATE tickets SET status='SOLD' WHERE booking_id=? AND status='AVAILABLE'
```
A ticket carries its claiming `booking_id` while reserved; `AVAILABLE → SOLD` is the only
status transition, and it happens exactly once because of the `WHERE` guard.

## Redis keys

| Key | Type | Purpose / TTL |
|---|---|---|
| `hold:{ticketId}` | string (userId) | distributed hold; `SET NX PX` 10 min; released via owner-checked Lua |
| `reserved:{eventId}` | ZSET member=ticketId, score=expiry epoch-ms | fast "is this seat taken right now" overlay for the UI; trimmed lazily |
| `queue:{eventId}` | ZSET member=sessionId, score=join-ms | waiting room FIFO |
| `admitted:{eventId}:{sessionId}` | string | admission token, TTL 15 min; booking-service checks before accepting reservations |
| `qcfg:{eventId}` | hash `ratePerSecond` | admin-tunable admission rate |
| `cache:event:{id}` | JSON | read-through catalog cache, TTL 1 h |
| `cache:search:{sha256}` | JSON | search result cache, TTL 60 s |

Redis is **never authoritative**: losing it loses holds/queue positions/caches only.
The correctness boundary is the Postgres claim (`docs/05`).

## Search index (`events`)

Fields: `name`, `description`, `performerName`, `venueName` (text), `city` (text + keyword),
`category` (keyword), `startsAt` (date). Docs upserted by the outbox relay; bulk rebuild via reindex endpoint.
