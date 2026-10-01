package com.tocketmaster.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Static event payload (event + venue + performer). Everything here changes rarely,
 * so it is cached aggressively at every layer: Redis read-through, CDN, browser.
 * Live seat availability is a separate endpoint so the static payload stays cacheable.
 */
public record EventDetail(
        UUID id,
        String name,
        String description,
        String category,
        OffsetDateTime startsAt,
        String status,
        boolean queueEnabled,
        VenueDto venue,
        PerformerDto performer) {
}
