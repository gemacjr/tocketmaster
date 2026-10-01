package com.tocketmaster.search.dto;

import java.time.OffsetDateTime;

public record SearchHit(
        String eventId,
        String name,
        String description,
        String category,
        OffsetDateTime startsAt,
        String performerName,
        String venueName,
        String city) {
}
