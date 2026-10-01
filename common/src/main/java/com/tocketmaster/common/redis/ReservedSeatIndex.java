package com.tocketmaster.common.redis;

import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-event sorted set of actively-held tickets.
 *
 * Member = ticketId, score = absolute expiry epoch-millis. Because the score IS the
 * expiry, the seat-map read path filters with ZRANGEBYSCORE (now, +inf) and lazily
 * trims stale members with ZREMRANGEBYSCORE — expired holds disappear from the seat
 * map on their own, even though their hold:{ticketId} keys have already expired in
 * Redis. This solves the read-path ghost-reservation problem the guide warns about
 * with plain Sets.
 */
public class ReservedSeatIndex {

    private final StatefulRedisConnection<String, String> connection;

    public ReservedSeatIndex(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    public void markReserved(String eventId, String ticketId, Instant expiresAt) {
        connection.sync().zadd(RedisKeys.reservedSet(eventId), expiresAt.toEpochMilli(), ticketId);
    }

    public void clearReserved(String eventId, String ticketId) {
        connection.sync().zrem(RedisKeys.reservedSet(eventId), ticketId);
    }

    /**
     * Snapshot of live (unexpired) holds for an event: ticketId -> expiresAt epoch ms.
     * Lazily trims expired entries while touching the set.
     */
    public Map<String, Long> activeHolds(String eventId) {
        long now = System.currentTimeMillis();
        var key = RedisKeys.reservedSet(eventId);
        var sync = connection.sync();

        // lazy cleanup of anything that expired before now
        sync.zremrangebyscore(key, 0, now);

        Map<String, Long> result = new LinkedHashMap<>();
        for (var entry : sync.zrangeWithScores(key, 0, -1)) {
            result.put(entry.getValue(), (long) entry.getScore());
        }
        return result;
    }
}
