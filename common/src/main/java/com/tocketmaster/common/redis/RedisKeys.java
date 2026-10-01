package com.tocketmaster.common.redis;

import java.util.List;

/**
 * Central registry of Redis key layouts.
 *
 * Distributed hold lock (per ticket):
 *   hold:{ticketId}            -> userId          (SET NX PX = reservation TTL)
 *
 * Reserved-seat sorted set (per event), scored by absolute expiry epoch millis so the
 * seat-map read path can filter/trim expired entries without any sweeper:
 *   reserved:{eventId}         -> ZSET(member=ticketId, score=expiresAtEpochMs)
 *
 * Virtual waiting room (per event):
 *   queue:{eventId}            -> ZSET(member=sessionId, score=joinTimeMillis)
 *   admitted:{eventId}:{sessionId} -> "1"        (TTL'd admission token)
 *   queuech:{eventId}          -> pub/sub channel for position updates
 *
 * Caches:
 *   cache:event:{eventId}      -> static event+venue+performer JSON (read-through)
 *   cache:search:{hash}        -> search result JSON
 */
public final class RedisKeys {

    public static String hold(String ticketId) {
        return "hold:" + ticketId;
    }

    public static String reservedSet(String eventId) {
        return "reserved:" + eventId;
    }

    public static String queue(String eventId) {
        return "queue:" + eventId;
    }

    public static String admitted(String eventId, String sessionId) {
        return "admitted:" + eventId + ":" + sessionId;
    }

    public static String queueChannel(String eventId) {
        return "queuech:" + eventId;
    }

    public static String eventCache(String eventId) {
        return "cache:event:" + eventId;
    }

    public static String searchCache(String normalizedQueryHash) {
        return "cache:search:" + normalizedQueryHash;
    }

    private RedisKeys() {
    }

    /**
     * Lua script implementing compare-and-delete so a holder can never release someone else's hold.
     */
    public static final String RELEASE_IF_OWNER =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) "
                    + "else return 0 end";

    public static List<String> releaseScriptKeys(String ticketId) {
        return List.of(hold(ticketId));
    }
}
