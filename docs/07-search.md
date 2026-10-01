# 07 — Search Pipeline

## Query path

```
GET /api/search?q=ember+kings&city=Austin
  → normalize (trim/lowercase) + SHA-256 → cache:search:{hash}
  → HIT: return (60s TTL)
  → MISS: OpenSearch events index:
        multi_match(query, fields=name^3,performerName^2,venueName,description,city,
                    fuzziness=AUTO)
        filter: startsAt >= now, optional term(category), match(city, fuzziness)
  → OpenSearch unreachable? Postgres ILIKE fallback on name/venue/performer/city
  → cache + return
```

## Index maintenance

- **Incremental**: admin event creation writes an `outbox` row in the same transaction;
  event-service relay polls every second → evicts catalog caches → POST `/internal/documents`
  to search-service.
- **Bulk**: `POST /api/admin/search/reindex` streams all upcoming events into the index and
  flushes `cache:search:*`.

`/internal/*` is edge-routed but intended for service-to-service traffic only; in AWS it's
reachable because services share one SG — a dedicated internal listener/VPC endpoint is the
prod hardening step.

## Why this shape

- Fuzzy matching is exactly what OpenSearch is for (`embar kings` → Ember Kings).
- The 60 s result cache absorbs most read load (guide: reads ≫ writes); invalidation is cheap
  because keys derive from queries, not entities — we just TTL-flush on reindex and let short
  TTL handle drift from incremental upserts.
- Postgres ILIKE fallback keeps the site up during an OpenSearch outage — availability over
  relevance for a degraded mode.
