package com.tocketmaster.event.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record VenueDto(
        UUID id,
        String name,
        String city,
        String country,
        String address,
        JsonNode seatMap) {
}
