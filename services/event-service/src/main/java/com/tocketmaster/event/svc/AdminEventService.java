package com.tocketmaster.event.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tocketmaster.common.api.NotFoundException;
import com.tocketmaster.event.domain.EventEntity;
import com.tocketmaster.event.domain.OutboxEntity;
import com.tocketmaster.event.dto.AdminEventRequest;
import com.tocketmaster.event.repo.EventRepository;
import com.tocketmaster.event.repo.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin/event-coordinator flow (below the line in the guide but needed to create
 * inventory). Creating an event materializes one ticket per seat in the venue's
 * seat map — same rule as the guide's high-level design.
 *
 * The mutation and its outbox row commit atomically; the relay handles cache
 * eviction + search indexing afterwards.
 */
@Service
public class AdminEventService {

    private final EventRepository events;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;

    public AdminEventService(EventRepository events, OutboxRepository outbox, JdbcTemplate jdbc) {
        this.events = events;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID createEvent(AdminEventRequest req) {
        UUID eventId = UUID.randomUUID();
        int created = jdbc.update("""
                        INSERT INTO events (id, name, description, category, performer_id, venue_id,
                                            starts_at, status, queue_enabled)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ONSALE', ?)
                        """,
                eventId, req.name(), req.description(), req.category(),
                req.performerId(), req.venueId(), req.startsAt(), req.queueEnabled());

        if (created == 0) {
            throw new IllegalStateException("event insert failed");
        }

        // one ticket per seat, priced by tier (mirrors infra/postgres/init.sql)
        jdbc.update("""
                        INSERT INTO tickets (event_id, section, seat_row, seat_num, price_cents)
                        SELECT ?, sec.code, rw.rn::TEXT, sn.sn,
                               CASE sec.tier WHEN 'PREMIUM' THEN 25000 WHEN 'MID' THEN 15000 ELSE 8000 END
                        FROM venues v
                        CROSS JOIN LATERAL jsonb_to_recordset(v.seat_map -> 'sections')
                            AS sec(code TEXT, "rows" INT, seats INT, tier TEXT)
                        CROSS JOIN LATERAL generate_series(1, sec."rows") AS rw(rn)
                        CROSS JOIN LATERAL generate_series(1, sec.seats) AS sn(sn)
                        WHERE v.id = ?
                        """,
                eventId, req.venueId());

        writeOutbox(eventId);
        return eventId;
    }

    @Transactional
    public void updateEvent(UUID eventId, AdminEventRequest req) {
        EventEntity event = events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("EVENT_NOT_FOUND", "No event " + eventId));
        event.setName(req.name());
        event.setDescription(req.description());
        event.setCategory(req.category());
        event.setStartsAt(req.startsAt());
        event.setQueueEnabled(req.queueEnabled());
        events.save(event);
        writeOutbox(eventId);
    }

    private void writeOutbox(UUID eventId) {
        OutboxEntity row = new OutboxEntity();
        row.setAggregateType("EVENT");
        row.setAggregateId(eventId);
        outbox.save(row);
    }
}
