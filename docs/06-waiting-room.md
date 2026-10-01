# 06 — Virtual Waiting Room

Problem (guide): an on-sale for a 20k-seat arena might draw 500k users in the first minute.
Letting them all hit inventory logic wastes money and lets latency-based races decide fairness.

## Design

```
join ──► ZADD queue:{eventId} now sessionId ──► position via ZRANK ──► SSE stream
                                            │
admission worker (@Scheduled 1s, queue-service):
  per enabled event: ZPOPMIN queue:{eventId} × ratePerSecond
  → SET admitted:{eventId}:{sessionId} TTL 900s
booking-service: reservation requires exists(admitted:{eventId}:{sessionId})
```

- **Ordering**: FIFO by join time — fairness is explicit and inspectable (`ZRANGE`).
- **Stateless services**: all state in Redis; any instance can serve any user; admission runs on
  one scheduler thread but is idempotent under retry.
- **Backpressure**: admitted tokens (25/s default, admin-tunable) cap concurrent reservation
  attempts at roughly rate × hold-window; Postgres sees hundreds of writes/sec, not millions.
- **UX**: SSE stream pushes `{position, queueSize}` every 2 s until `event: admitted`.
  Polling fallback endpoint exists for restrictive proxies.

## Trade-offs / notes

- One Redis is a scaling ceiling for queue ops (~100k ops/s); shard per event if needed
  (`queue:{eventId}` hashes to one node).
- Tokens are single-use by convention, not enforcement — a user could re-reserve within their
  token TTL. Acceptable: Postgres still enforces correctness.
- If Redis dies mid-on-sale, joins fail closed (403) — better than silently bypassing the room;
  `queue_enabled=false` events are unaffected.
- Local demo: event seed `…0001` has `queue_enabled=TRUE`; others bypass the room.
