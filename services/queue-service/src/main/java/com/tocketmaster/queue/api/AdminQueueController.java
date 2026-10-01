package com.tocketmaster.queue.api;

import com.tocketmaster.common.redis.RedisKeys;
import com.tocketmaster.queue.svc.WaitingRoomService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AdminQueueController {

    private final WaitingRoomService room;

    public AdminQueueController(WaitingRoomService room) {
        this.room = room;
    }

    /** PUT /api/admin/queue/{eventId} — tune admission throughput for a hot event. */
    @PutMapping("/api/admin/queue/{eventId}")
    public Map<String, Object> configure(@PathVariable String eventId,
                                         @RequestBody(required = false) Map<String, Integer> body) {
        int rate = body == null || body.get("ratePerSecond") == null
                ? WaitingRoomService.DEFAULT_RATE_PER_SEC
                : body.get("ratePerSecond");
        room.setAdmissionRate(eventId, rate);
        return Map.of("eventId", eventId, "ratePerSecond", room.admissionRate(eventId));
    }
}
