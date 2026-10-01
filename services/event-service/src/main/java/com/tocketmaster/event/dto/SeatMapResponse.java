package com.tocketmaster.event.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Live seat availability for the interactive seat map. NOT cached server-side beyond
 * milliseconds: status per seat is AVAILABLE / HELD (Redis hold, unexpired) / SOLD.
 */
public record SeatMapResponse(
        UUID eventId,
        Instant generatedAt,
        List<SeatSection> sections,
        Counts counts) {

    public record Counts(int available, int held, int sold) {
    }
}
