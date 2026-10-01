package com.tocketmaster.common.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Distributed hold locks with TTL — the guide's chosen "Great Solution" for ticket
 * reservations.
 *
 * Semantics:
 *  - Acquire is atomic: SET hold:{ticketId} owner NX PX ttl. Exactly one client wins.
 *  - Expiry is automatic: if the buyer abandons checkout, the key evaporates after the
 *    TTL and the seat becomes bookable again with zero sweeper involvement on the hot path.
 *  - Release is guarded: a Lua compare-and-delete guarantees only the owning user can
 *    release a hold early (never clobbers a newer holder).
 *  - Multi-seat checkout acquires sequentially; on any failure every already-acquired
 *    hold is rolled back so partial holds never leak.
 *
 * Correctness note: Redis holds are an availability/UX optimization. The database
 * remains the final arbiter of ownership via optimistic concurrency (status +
 * precondition UPDATE), so even a total Redis outage can never produce a double sale.
 */
public class HoldManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HoldManager.class);

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public HoldManager(String redisUri) {
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
    }

    public StatefulRedisConnection<String, String> connection() {
        return connection;
    }

    /**
     * Try to acquire a single hold. Returns false if the seat is currently held by anyone.
     */
    public boolean tryAcquire(String ticketId, String ownerId, Duration ttl) {
        String res = connection.sync().set(
                RedisKeys.hold(ticketId), ownerId, SetArgs.Builder.nx().px(ttl.toMillis()));
        return "OK".equals(res);
    }

    /**
     * All-or-nothing acquisition of many tickets for one buyer. On the first failure,
     * every hold acquired so far in THIS call is released before returning.
     */
    public void acquireAllOrNothing(List<String> ticketIds, String ownerId, Duration ttl) {
        List<String> acquired = new ArrayList<>(ticketIds.size());
        Map<String, String> failures = new LinkedHashMap<>();
        try {
            for (String ticketId : ticketIds) {
                if (tryAcquire(ticketId, ownerId, ttl)) {
                    acquired.add(ticketId);
                } else {
                    failures.put(ticketId, "HELD_BY_OTHER");
                }
            }
        } catch (RuntimeException e) {
            // transport-level failure mid-loop: roll back what we got, then rethrow
            releaseAll(acquired, ownerId);
            throw e;
        }
        if (!failures.isEmpty()) {
            releaseAll(acquired, ownerId);
            throw new com.tocketmaster.common.api.ConflictException(
                    "TICKETS_HELD",
                    "Some requested seats are no longer available",
                    Map.of("conflictingTicketIds", failures.keySet()));
        }
    }

    /**
     * Compare-and-delete release: returns true if we actually released our own hold.
     */
    public boolean release(String ticketId, String ownerId) {
        Long res = connection.sync().eval(
                RedisKeys.RELEASE_IF_OWNER,
                ScriptOutputType.INTEGER,
                new String[]{RedisKeys.hold(ticketId)},
                ownerId);
        return res != null && res == 1;
    }

    public void releaseAll(List<String> ticketIds, String ownerId) {
        for (String ticketId : ticketIds) {
            boolean ok = release(ticketId, ownerId);
            if (!ok) {
                log.debug("Hold for {} not owned by {} (already expired or re-held); skipping", ticketId, ownerId);
            }
        }
    }

    /**
     * Current owner of a hold, or null if none/expired.
     */
    public String ownerOf(String ticketId) {
        return connection.sync().get(RedisKeys.hold(ticketId));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
