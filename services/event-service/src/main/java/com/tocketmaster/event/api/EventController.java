package com.tocketmaster.event.api;

import com.tocketmaster.event.cache.EventCacheService;
import com.tocketmaster.event.dto.EventDetail;
import com.tocketmaster.event.dto.EventSummary;
import com.tocketmaster.event.dto.SeatMapResponse;
import com.tocketmaster.event.repo.EventRepository;
import com.tocketmaster.event.svc.SeatMapService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventCacheService cache;
    private final SeatMapService seatMapService;
    private final EventRepository events;

    public EventController(EventCacheService cache,
                           SeatMapService seatMapService,
                           EventRepository events) {
        this.cache = cache;
        this.seatMapService = seatMapService;
        this.events = events;
    }

    /**
     * GET /api/events/{id} — static detail payload, read-through cached (1h TTL),
     * CDN-cacheable. Availability lives in /seats so this stays aggressively cacheable.
     */
    @GetMapping("/{eventId}")
    public ResponseEntity<EventDetail> event(@PathVariable String eventId) {
        EventDetail detail = cache.getThrough(java.util.UUID.fromString(eventId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5))
                        .cachePublic()
                        .staleWhileRevalidate(java.time.Duration.ofHours(1)))
                .body(detail);
    }

    /**
     * GET /api/events/{id}/seats — live seat map: DB truth + Redis hold overlay.
     */
    @GetMapping("/{eventId}/seats")
    public SeatMapResponse seats(@PathVariable String eventId) {
        return seatMapService.seatMap(java.util.UUID.fromString(eventId));
    }

    /**
     * GET /api/events?page=&size=&category= — simple upcoming-events browse.
     */
    @GetMapping
    public List<EventSummary> list(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(required = false) String category) {
        PageRequest pr = PageRequest.of(page, Math.min(size, 100));
        var now = OffsetDateTime.now();
        Page<com.tocketmaster.event.domain.EventEntity> result =
                (category == null || category.isBlank())
                        ? events.findByStartsAtAfterOrderByStartsAtAsc(now, pr)
                        : events.findByCategoryAndStartsAtAfterOrderByStartsAtAsc(category, now, pr);

        return result.map(e -> new EventSummary(
                e.getId(), e.getName(), e.getCategory(), e.getStartsAt(), e.isQueueEnabled(),
                null, null, null)).getContent();
    }
}
