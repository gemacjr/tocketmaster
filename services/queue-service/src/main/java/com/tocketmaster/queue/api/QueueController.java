package com.tocketmaster.queue.api;

import com.tocketmaster.common.api.ApiException;
import com.tocketmaster.queue.svc.WaitingRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class QueueController {

    private final WaitingRoomService room;
    private final ThreadPoolTaskScheduler scheduler;

    public QueueController(WaitingRoomService room) {
        this.room = room;
        this.scheduler = new ThreadPoolTaskScheduler();
        this.scheduler.setPoolSize(16);
        this.scheduler.initialize();
    }

    public record JoinBody(String sessionId) {}

    /** POST /api/queue/events/{eventId}/join — enter (or re-enter) the waiting room. */
    @PostMapping("/api/queue/events/{eventId}/join")
    public Map<String, Object> join(@PathVariable String eventId,
                                    @RequestBody(required = false) JoinBody body,
                                    @RequestHeader(value = "X-Session-Id", required = false) String headerSession) {
        String sessionId = sessionId(body, headerSession);
        var status = room.join(eventId, sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("enabled", status.enabled());
        out.put("status", status.status());
        out.put("position", status.position());
        out.put("queueSize", status.queueSize());
        return out;
    }

    @GetMapping("/api/queue/events/{eventId}/status")
    public Map<String, Object> status(@PathVariable String eventId,
                                      @RequestParam(required = false) String sessionId,
                                      @RequestHeader(value = "X-Session-Id", required = false) String headerSession) {
        String s = (sessionId != null && !sessionId.isBlank()) ? sessionId : headerSession;
        var st = (s == null || s.isBlank()) ? null : room.status(eventId, s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queueSize", room.queueSize(eventId));
        if (st != null) {
            out.put("sessionId", s);
            out.put("status", st.status());
            out.put("position", st.position());
        }
        return out;
    }

    /**
     * GET /api/queue/events/{eventId}/stream — SSE position updates every 2s until
     * admitted or disconnected. Each connection pulls its own ZRANK from shared Redis,
     * so any queue-service instance can serve any connection.
     */
    @GetMapping(path = "/api/queue/events/{eventId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String eventId,
                                             @RequestParam String sessionId) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(45).toMillis());

        var future = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (room.isAdmitted(eventId, sessionId)) {
                    emitter.send(SseEmitter.event().name("admitted")
                            .data(Map.of("sessionId", sessionId)));
                    emitter.complete();
                    return;
                }
                Long position = room.positionOf(eventId, sessionId);
                long size = room.queueSize(eventId);
                emitter.send(SseEmitter.event().name("position")
                        .data(Map.of(
                                "position", position == null ? -1 : position,
                                "queueSize", size,
                                "estimatedWaitMinutes", estimate(position))));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, Duration.ofSeconds(2));

        Runnable cleanup = () -> {
            future.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private long estimate(Long position) {
        if (position == null || position <= 0) {
            return 0;
        }
        return Math.round((double) position / WaitingRoomService.DEFAULT_RATE_PER_SEC);
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handle(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.valueOf(e.httpStatus()));
        pd.setTitle(e.getCode());
        pd.setDetail(e.getMessage());
        return pd;
    }

    private static String sessionId(JoinBody body, String headerSession) {
        if (body != null && body.sessionId() != null && !body.sessionId().isBlank()) {
            return body.sessionId();
        }
        if (headerSession != null && !headerSession.isBlank()) {
            return headerSession;
        }
        return "sess-" + UUID.randomUUID();
    }
}
