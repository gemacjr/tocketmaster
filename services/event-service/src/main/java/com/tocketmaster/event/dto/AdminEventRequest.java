package com.tocketmaster.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminEventRequest(
        UUID performerId,
        UUID venueId,
        String name,
        String description,
        String category,
        OffsetDateTime startsAt,
        boolean queueEnabled) {
}
