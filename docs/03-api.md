# 03 — API Reference

All endpoints are JSON over HTTP behind the edge (nginx :18080 locally / ALB in AWS).
Identity stand-in: `X-User-Id` header (defaults to `guest`). Waiting-room sessions: `X-Session-Id`.

## event-service (:8081, edge `/api/events`, `/api/admin/events`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/events?q=&city=` | list w/ optional filters |
| GET | `/api/events/{id}` | detail + `Cache-Control: public, max-age=60` |
| GET | `/api/events/{id}/seats` | live seat map: `AVAILABLE/HELD/SOLD` per seat + counts (poll every 5 s) |
| POST | `/api/admin/events` | create event + venue seat materialization + outbox row |

## search-service (:8082, edge `/api/search`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/search?q=&category=&city=&from=0&size=20` | fuzzy multi_match; 60 s Redis cache; Postgres fallback |
| POST | `/api/admin/search/reindex` | rebuild index from Postgres, flush result cache |
| POST | `/internal/documents` | upsert one doc (called by event-service relay) — internal SG only |

## booking-service (:8083, edge `/api/bookings`, `/api/payments`, `/api/users`)

| Method | Path | Body / notes |
|---|---|---|
| POST | `/api/bookings/reservations` | `{eventId, ticketIds[], sessionId?}` → all-or-nothing holds + PENDING booking; `409 CONFLICT` if any seat is taken/held; waiting-room gate returns `403 WAITING_ROOM_REQUIRED` |
| POST | `/api/bookings/{id}/confirm` | charges via gateway; booking stays PENDING until webhook |
| POST | `/api/bookings/{id}/cancel` | releases claims + holds |
| GET | `/api/bookings/{id}` | owner-checked status (`CREATED/PENDING/CONFIRMED/EXPIRED/CANCELLED/REFUNDED`) |
| GET | `/api/users/me/bookings` | history for `X-User-Id` |
| POST | `/api/payments/webhook` | gateway → us. Idempotent on `(paymentId)`; finalizes with OCC; auto-refunds losers |

### Reservation response

```json
{
  "bookingId": "…", "status": "PENDING",
  "expiresAt": "2026-08-25T09:35:00Z",
  "totalCents": 25998,
  "tickets": [{"id":"…","tier":"TIER_1","priceCents":12999,"row":"A","seatNumber":"12"}]
}
```

## queue-service (:8084, edge `/api/queue`)

| Method | Path | Notes |
|---|---|---|
| POST | `/api/queue/events/{eventId}/join` | header `X-Session-Id`; returns `{status: WAITING\|ADMITTED, position, queueSize}` |
| GET | `/api/queue/events/{eventId}/status` | side-effect-free status probe |
| GET | `/api/queue/events/{eventId}/stream` | SSE; pushes position until admitted, then `event: admitted` and closes |
| PUT | `/api/admin/queue/events/{eventId}/admission-rate` | `{ratePerSecond}` |

## Error envelope

```json
{"status": 409, "error": "CONFLICT", "message": "Some seats are no longer available"}
```

`400 VALIDATION · 403 FORBIDDEN/WAITING_ROOM_REQUIRED · 404 NOT_FOUND · 409 CONFLICT · 500`
