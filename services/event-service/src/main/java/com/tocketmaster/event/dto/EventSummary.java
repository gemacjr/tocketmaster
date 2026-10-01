package com.tocketmaster.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EventSummary(
        UUID id,
        String name,
        String category,
        OffsetDateTime startsAt,
        boolean queueEnabled,
        String performerName,
        String venueName,
        String city) {
}
