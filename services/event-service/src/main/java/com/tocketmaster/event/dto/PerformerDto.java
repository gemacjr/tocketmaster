package com.tocketmaster.event.dto;

import java.util.UUID;

public record PerformerDto(UUID id, String name, String category, String description) {
}
