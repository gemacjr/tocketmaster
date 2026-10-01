package com.tocketmaster.event.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tocketmaster.common.api.NotFoundException;
import com.tocketmaster.common.redis.RedisKeys;
import com.tocketmaster.event.domain.EventEntity;
import com.tocketmaster.event.domain.PerformerEntity;
import com.tocketmaster.event.domain.VenueEntity;
import com.tocketmaster.event.dto.EventDetail;
import com.tocketmaster.event.dto.PerformerDto;
import com.tocketmaster.event.dto.VenueDto;
import com.tocketmaster.event.repo.EventRepository;
import com.tocketmaster.event.repo.PerformerRepository;
import com.tocketmaster.event.repo.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Read-through cache for the static event payload (event + venue + performer).
 *
 * Layering per docs/06-scaling-reads.md:
 *   CloudFront/CDN -> Redis read-through -> PostgreSQL.
 * Static payload TTL is long (1h) because mutations are rare; the outbox relay
 * evicts proactively on any admin mutation, so TTL is only a safety net.
 * The seat-map endpoint deliberately bypasses this cache (availability is hot data).
 */
@Service
public class EventCacheService {

    private static final Logger log = LoggerFactory.getLogger(EventCacheService.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final EventRepository events;
    private final VenueRepository venues;
    private final PerformerRepository performers;
    private final com.tocketmaster.common.redis.HoldManager holdManager;
    private final ObjectMapper mapper;

    public EventCacheService(EventRepository events,
                             VenueRepository venues,
                             PerformerRepository performers,
                             com.tocketmaster.common.redis.HoldManager holdManager,
                             ObjectMapper mapper) {
        this.events = events;
        this.venues = venues;
        this.performers = performers;
        this.holdManager = holdManager;
        this.mapper = mapper;
    }

    public EventDetail getThrough(UUID eventId) {
        String key = RedisKeys.eventCache(eventId.toString());
        try {
            String hit = holdManager.connection().sync().get(key);
            if (hit != null) {
                return mapper.readValue(hit, EventDetail.class);
            }
        } catch (Exception e) {
            log.warn("Cache read failed for {}, falling through to DB: {}", key, e.getMessage());
        }

        EventDetail detail = loadFromDb(eventId);
        try {
            holdManager.connection().sync().setex(key, TTL.toSeconds(), mapper.writeValueAsString(detail));
        } catch (Exception e) {
            log.warn("Cache write failed for {}: {}", key, e.getMessage());
        }
        return detail;
    }

    public void evict(UUID eventId) {
        try {
            holdManager.connection().sync().del(RedisKeys.eventCache(eventId.toString()));
        } catch (Exception e) {
            log.warn("Cache eviction failed for {}: {}", eventId, e.getMessage());
        }
    }

    public EventDetail loadFromDb(UUID eventId) {
        EventEntity event = events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "No event " + eventId));
        VenueEntity venue = venues.findById(event.getVenueId())
                .orElseThrow(() -> new NotFoundException("VENUE_NOT_FOUND", "No venue " + event.getVenueId()));
        PerformerEntity performer = performers.findById(event.getPerformerId())
                .orElseThrow(() -> new NotFoundException("PERFORMER_NOT_FOUND", "No performer"));

        return new EventDetail(
                event.getId(), event.getName(), event.getDescription(), event.getCategory(),
                event.getStartsAt(), event.getStatus(), event.isQueueEnabled(),
                new VenueDto(venue.getId(), venue.getName(), venue.getCity(),
                        venue.getCountry(), venue.getAddress(), parse(venue.getSeatMap())),
                new PerformerDto(performer.getId(), performer.getName(),
                        performer.getCategory(), performer.getDescription()));
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return mapper.readTree(json == null ? "[]" : json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
