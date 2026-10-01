package com.tocketmaster.queue.svc;

import com.tocketmaster.common.redis.HoldManager;
import com.tocketmaster.common.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Virtual waiting room (the guide's "Great Solution" for extreme-demand events).
 *
 * Ordering: Redis sorted set per event, score = join time (FIFO).
 * Admission: a worker admits N sessions/second from the head, stamping each with an
 * admission token key (TTL'd). The Booking Service checks that token before accepting
 * reservations, so the room throttles how many users can contend for inventory at once.
 *
 * Position updates are pulled by each SSE connection (ZRANK on shared Redis), which
 * keeps any instance stateless and horizontally scalable — no pub/sub fan-out needed.
 */
@Service
public class WaitingRoomService {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);

    public static final int ADMISSION_TTL_SECONDS = 15 * 60;
    public static final int DEFAULT_RATE_PER_SEC = 25;
    private static final String RATE_KEY = "qcfg:%s";

    public record QueueStatus(boolean enabled, String status, Long position, long queueSize) {}

    private final HoldManager redis;
    private final JdbcTemplate jdbc;

    public WaitingRoomService(HoldManager redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ user actions

    public QueueStatus join(String eventId, String sessionId) {
        boolean enabled = isEnabled(eventId);
        var sync = redis.connection().sync();

        if (!enabled || sync.exists(RedisKeys.admitted(eventId, sessionId)) > 0) {
            return new QueueStatus(enabled, "ADMITTED", 0L, sync.zcard(RedisKeys.queue(eventId)));
        }

        Long rank = sync.zrank(RedisKeys.queue(eventId), sessionId);
        if (rank == null) {
            sync.zadd(RedisKeys.queue(eventId), System.currentTimeMillis(), sessionId);
            rank = sync.zrank(RedisKeys.queue(eventId), sessionId);
        }
        return new QueueStatus(true, "WAITING", rank + 1, sync.zcard(RedisKeys.queue(eventId)));
    }

    public boolean isAdmitted(String eventId, String sessionId) {
        return redis.connection().sync().exists(RedisKeys.admitted(eventId, sessionId)) > 0;
    }

    /** Status without side effects: never enqueues the session. */
    public QueueStatus status(String eventId, String sessionId) {
        var sync = redis.connection().sync();
        long size = sync.zcard(RedisKeys.queue(eventId));

        if (!isEnabled(eventId) || sync.exists(RedisKeys.admitted(eventId, sessionId)) > 0) {
            return new QueueStatus(true, "ADMITTED", 0L, size);
        }
        Long rank = sync.zrank(RedisKeys.queue(eventId), sessionId);
        return new QueueStatus(true, "WAITING", rank == null ? null : rank + 1, size);
    }

    public long queueSize(String eventId) {
        Long size = redis.connection().sync().zcard(RedisKeys.queue(eventId));
        return size == null ? 0 : size;
    }

    public Long positionOf(String eventId, String sessionId) {
        Long rank = redis.connection().sync().zrank(RedisKeys.queue(eventId), sessionId);
        return rank == null ? null : rank + 1;
    }

    // ------------------------------------------------------------------ admin

    public void setAdmissionRate(String eventId, int ratePerSecond) {
        redis.connection().sync()
                .hset(String.format(RATE_KEY, eventId), "ratePerSecond", String.valueOf(ratePerSecond));
    }

    public int admissionRate(String eventId) {
        String v = redis.connection().sync().hget(String.format(RATE_KEY, eventId), "ratePerSecond");
        try {
            return v == null ? DEFAULT_RATE_PER_SEC : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return DEFAULT_RATE_PER_SEC;
        }
    }

    // ------------------------------------------------------------------ admission worker

    /**
     * Every second: admit up to `rate` sessions from the head of each active queue.
     */
    @Scheduled(fixedDelay = 1000)
    public void admitTick() {
        List<UUID> enabledEvents = jdbc.query(
                "select id from events where queue_enabled and starts_at > now()",
                (rs, i) -> rs.getObject("id", UUID.class));

        for (UUID eventId : enabledEvents) {
            try {
                admitBatch(eventId.toString());
            } catch (RuntimeException e) {
                log.warn("Admission tick failed for {}: {}", eventId, e.getMessage());
            }
        }
    }

    void admitBatch(String eventId) {
        var sync = redis.connection().sync();
        int rate = admissionRate(eventId);
        for (int i = 0; i < rate; i++) {
            var popped = sync.zpopmin(RedisKeys.queue(eventId));
            if (popped.isEmpty()) {
                break;
            }
            sync.setex(RedisKeys.admitted(eventId, popped.getValue()),
                    ADMISSION_TTL_SECONDS, "1");
        }
    }

    boolean isEnabled(String eventId) {
        Boolean e = jdbc.queryForObject(
                "select queue_enabled from events where id = ?", Boolean.class,
                UUID.fromString(eventId));
        return Boolean.TRUE.equals(e);
    }
}
