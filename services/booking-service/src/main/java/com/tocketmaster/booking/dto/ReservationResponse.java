package com.tocketmaster.booking.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ReservationResponse(
        String bookingId,
        String status,
        int totalCents,
        OffsetDateTime expiresAt,
        List<String> ticketIds) {
}
