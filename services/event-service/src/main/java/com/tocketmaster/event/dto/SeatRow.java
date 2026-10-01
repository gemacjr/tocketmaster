package com.tocketmaster.event.dto;

import java.util.List;

public record SeatRow(String row, List<SeatView> seats) {
}
