package com.tocketmaster.event.dto;

import java.util.List;

public record SeatSection(String code, String tier, List<SeatRow> rows) {
}
