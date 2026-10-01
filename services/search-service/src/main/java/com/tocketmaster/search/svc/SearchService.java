package com.tocketmaster.search.svc;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tocketmaster.common.redis.HoldManager;
import com.tocketmaster.common.redis.RedisKeys;
import com.tocketmaster.search.dto.SearchHit;
import com.tocketmaster.search.repo.EventDocRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Search pipeline: Redis result cache (60s) -> OpenSearch (inverted index, fuzzy
 * multi_match, shard-level request cache) -> PostgreSQL ILIKE fallback.
 *
 * Cache keys are the fully-normalized query so identical searches from any user hit
 * the same entry — safe to also serve from a CDN since results are non-personalized.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    // jsr310 module: OffsetDateTime serialization for SearchHit/doc payloads
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record SearchResult(List<SearchHit> hits, String source, long tookMs) {}

    private final OpenSearchClient opensearch;
    private final EventDocRepository docs;
    private final HoldManager redis;

    public SearchService(OpenSearchClient opensearch,
                         EventDocRepository docs,
                         HoldManager redis,
                         @Value("${search.fallback-enabled:true}") boolean fallbackEnabled) {
        this.opensearch = opensearch;
        this.docs = docs;
        this.redis = redis;
        this.fallbackEnabled = fallbackEnabled;
    }

    private final boolean fallbackEnabled;

    public SearchResult search(String q, String category, String city, int page, int size) {
        long start = System.currentTimeMillis();
        int from = Math.max(page, 0) * size;
        int limit = Math.min(Math.max(size, 1), 50);

        String cacheKey = RedisKeys.searchCache(cacheHash(q, category, city, page, limit));
        try {
            String cached = redis.connection().sync().get(cacheKey);
            if (cached != null) {
                List<SearchHit> hits = MAPPER
                        .readValue(cached,
                                new com.fasterxml.jackson.core.type.TypeReference<List<SearchHit>>() {});
                return new SearchResult(hits, "cache", elapsed(start));
            }
        } catch (Exception e) {
            log.warn("Search cache read failed: {}", e.getMessage());
        }

        if (opensearch.ping()) {
            try {
                opensearch.ensureIndex();
                var response = opensearch.search(q, category, city, from, limit);
                List<SearchHit> hits = extract(response);
                putCache(cacheKey, hits);
                return new SearchResult(hits, "opensearch", elapsed(start));
            } catch (Exception e) {
                log.warn("OpenSearch query failed, degrading to DB: {}", e.getMessage());
            }
        }

        if (!fallbackEnabled) {
            return new SearchResult(List.of(), "unavailable", elapsed(start));
        }
        // degraded mode: availability-first read path straight off PostgreSQL
        int offset = Math.max(page, 0) * limit;
        List<SearchHit> hits = new ArrayList<>(docs.fallbackSearch(q, category, offset + limit));
        if (offset > 0 && offset < hits.size()) {
            hits = hits.subList(offset, hits.size());
        }
        return new SearchResult(hits, "db-fallback", elapsed(start));
    }

    public int reindex() {
        opensearch.ensureIndex();
        int n = 0;
        for (EventDocRepository.DocRow row : docs.allUpcoming()) {
            var mapper = MAPPER;
            ObjectNode doc = mapper.createObjectNode();
            doc.put("eventId", row.eventId());
            doc.put("name", row.name());
            doc.put("description", row.description());
            doc.put("category", row.category().toUpperCase());
            doc.put("startsAt", row.startsAt() == null ? null : row.startsAt().toString());
            doc.put("performerName", row.performerName());
            doc.put("venueName", row.venueName());
            doc.put("city", row.city());

            try {
                opensearch.indexDocument(row.eventId(), mapper.writeValueAsString(doc));
                n++;
            } catch (Exception e) {
                log.warn("Reindex failed for {}: {}", row.eventId(), e.getMessage());
            }
        }
        flushCache();
        return n;
    }

    /** Upsert used by the event-service outbox relay. */
    public void upsert(String eventId, com.fasterxml.jackson.databind.JsonNode doc) {
        opensearch.ensureIndex();
        opensearch.indexDocument(eventId, doc.toString());
    }

    private void putCache(String key, List<SearchHit> hits) {
        try {
            String json = MAPPER.writeValueAsString(hits);
            redis.connection().sync().setex(key, CACHE_TTL.toSeconds(), json);
        } catch (Exception e) {
            log.debug("Search cache write failed: {}", e.getMessage());
        }
    }

    private void flushCache() {
        try {
            var conn = redis.connection().sync();
            for (String key : conn.keys("cache:search:*")) {
                conn.del(key);
            }
        } catch (Exception e) {
            log.debug("Search cache flush failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<SearchHit> extract(com.fasterxml.jackson.databind.JsonNode response) {
        List<SearchHit> hits = new ArrayList<>();
        for (var hit : response.path("hits").path("hits")) {
            var src = hit.path("_source");
            hits.add(MAPPER.convertValue(src, SearchHit.class));
        }
        return hits;
    }

    private static String cacheHash(String q, String category, String city, int page, int size) {
        String normalized = String.join("|",
                q == null ? "" : q.trim().toLowerCase(),
                category == null ? "" : category.trim().toUpperCase(),
                city == null ? "" : city.trim().toLowerCase(),
                String.valueOf(page), String.valueOf(size));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
