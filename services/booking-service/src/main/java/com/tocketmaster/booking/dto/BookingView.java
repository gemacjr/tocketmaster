package com.tocketmaster.booking.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record BookingView(
        String bookingId,
        String eventId,
        String status,
        int totalCents,
        OffsetDateTime expiresAt,
        List<Seat> seats) {

    public record Seat(String ticketId, String section, String row, int num, int priceCents) {}
}
