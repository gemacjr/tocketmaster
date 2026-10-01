# 05 — Ticketing Deep Dive: holds, OCC, payments

This is the heart of the system. Goal: **a seat is never sold twice**, and a user never
pays for a seat they don't get.

## Phase 1 — Reserve (user clicks seats → "reserve")

```
1. Waiting-room gate   queue-service admitted-token in Redis? (hot events only)  → 403 if not admitted
2. Fast-fail hold      Redis: SET hold:{ticketId} userId NX PX 600_000 for ALL tickets
                       (any miss ⇒ release all, 409 CONFLICT)
3. Validate            all tickets exist, belong to eventId, status=AVAILABLE
4. DB claim            BEGIN
                         INSERT booking (status=PENDING, expires_at=now+TTL)
                         UPDATE tickets SET booking_id=:b
                          WHERE id IN (:ids) AND status='AVAILABLE'
                       COMMIT  -- rowcount < N ⇒ someone raced us ⇒ throw
5. Loser cleanup       releaseClaims(bookingId) + delete holds; surface 409
6. Mark overlay        ZADD reserved:{eventId} expiryMs ticketIds  (UI polling)
```

- **Why both Redis holds and Postgres claims?** Redis absorbs the thundering herd cheaply
  (99% of losing requests die at step 2 without touching Postgres). Postgres decides the winner.
  The guide's framing: Redis = availability/experience, Postgres = correctness.
- **All-or-nothing**: partial bookings of a seat group are worse than failure. Holds acquire all
  or roll back all; claims are one UPDATE, inherently atomic per statement.
- `expires_at` on the booking makes every later step able to detect staleness.

## Phase 2 — Pay (confirm)

1. Ownership + status check (`PENDING`, unexpired).
2. Call gateway with idempotency key (= payment intent). Mock gateway responds `succeeded`
   asynchronously by POSTing `/api/payments/webhook` back to us over HTTP (real Stripe shape).
3. Booking remains PENDING until the webhook arrives. Client polls `GET /bookings/{id}`.

## Phase 3 — Finalize (webhook)

```sql
BEGIN;
  SELECT ... FROM payments WHERE provider_ref=? FOR UPDATE;      -- idempotency gate
  IF already PROCESSED THEN return 200;                          -- duplicate delivery: no-op
  IF booking has no claimed seats THEN refund; mark FAILED; END IF;
  UPDATE bookings SET status='CONFIRMED' WHERE id=? AND status='PENDING';
  UPDATE tickets SET status='SOLD'    WHERE booking_id=? AND status='AVAILABLE';
  IF rowcount <> expected THEN                                   -- lost the race (hold expired,
    UPDATE bookings SET status='FAILED';                         // seat re-claimed by another buyer)
    gateway.refund(...);
  ELSE mark payment SUCCEEDED;
COMMIT;
POST /internal/documents (outbox relay) → search index refresh
```

The `rowcount <> expected` branch is the money shot: it means the hold expired mid-payment and
another buyer claimed the seats. We refund automatically — **the loser pays nothing**.

## Sweeper (reclaims abandoned carts)

Every 30 s: find `status='PENDING' AND expires_at < now()` → CAS to `EXPIRED` →
release ticket claims (`WHERE booking_id=? AND status='AVAILABLE'`) → delete Redis holds →
outbox evict. Expired-PENDING bookings are also excluded from the *claim* query itself
(`NOT EXISTS (... status='PENDING' AND expires_at > now())`), so a freed seat can be re-sold
the instant its TTL lapses even before the sweeper runs.

## Why this can't double-sell

Claim exclusivity is a single-statement invariant: a ticket row is claimed by at most one
non-expired booking, and the final flip `AVAILABLE → SOLD WHERE booking_id=?` can succeed for
exactly one booking. Two transactions racing on the same row serialize on its row lock;
exactly one sees rowcount=N. Everything else (Redis, caches, the UI) is advisory.
