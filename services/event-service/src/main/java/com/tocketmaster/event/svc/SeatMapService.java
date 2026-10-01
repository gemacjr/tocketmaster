package com.tocketmaster.event.svc;

import com.fasterxml.jackson.databind.JsonNode;
import com.tocketmaster.common.api.NotFoundException;
import com.tocketmaster.common.redis.ReservedSeatIndex;
import com.tocketmaster.event.cache.EventCacheService;
import com.tocketmaster.event.dto.SeatMapResponse;
import com.tocketmaster.event.dto.SeatRow;
import com.tocketmaster.event.dto.SeatSection;
import com.tocketmaster.event.dto.SeatView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the live seat map. Ticket rows come from PostgreSQL (AVAILABLE/SOLD truth);
 * Redis holds are overlaid on top as HELD with lazy expiry trimming, so an abandoned
 * checkout frees its seat on the map the instant its TTL lapses — no sweeper needed
 * for display correctness.
 */
@Service
public class SeatMapService {

    private record Row(UUID id, String section, String seatRow, int seatNum, int priceCents, String status) {}

    private final JdbcTemplate jdbc;
    private final ReservedSeatIndex reservedSeatIndex;

    public SeatMapService(JdbcTemplate jdbc, ReservedSeatIndex reservedSeatIndex) {
        this.jdbc = jdbc;
        this.reservedSeatIndex = reservedSeatIndex;
    }

    public SeatMapResponse seatMap(UUID eventId) {
        List<Row> rows = jdbc.query("""
                        SELECT id, section, seat_row, seat_num, price_cents, status
                        FROM tickets WHERE event_id = ? ORDER BY section, seat_row::int, seat_num
                        """,
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("section"),
                        rs.getString("seat_row"),
                        rs.getInt("seat_num"),
                        rs.getInt("price_cents"),
                        rs.getString("status")),
                eventId);

        if (rows.isEmpty()) {
            throw new NotFoundException("EVENT_NOT_FOUND", "No event " + eventId);
        }

        Map<String, Long> activeHolds = reservedSeatIndex.activeHolds(eventId.toString());

        var bySection = new java.util.LinkedHashMap<String, List<Row>>();
        for (Row r : rows) {
            bySection.computeIfAbsent(r.section(), k -> new ArrayList<>()).add(r);
        }

        int available = 0, held = 0, sold = 0;
        List<SeatSection> sections = new ArrayList<>();

        for (var entry : bySection.entrySet()) {
            String tier = tierFor(entry.getValue());
            var rowGroups = new java.util.TreeMap<Integer, List<Row>>();
            for (Row r : entry.getValue()) {
                rowGroups.computeIfAbsent(Integer.parseInt(r.seatRow()), k -> new ArrayList<>()).add(r);
            }

            List<SeatRow> outRows = new ArrayList<>();
            for (var rg : rowGroups.entrySet()) {
                List<SeatView> seats = new ArrayList<>(rg.getValue().size());
                for (Row r : rg.getValue()) {
                    String status;
                    switch (r.status()) {
                        case "SOLD" -> { status = "SOLD"; sold++; }
                        default -> {
                            if (activeHolds.containsKey(r.id().toString())) { status = "HELD"; held++; }
                            else { status = "AVAILABLE"; available++; }
                        }
                    }
                    seats.add(new SeatView(r.id().toString(), r.seatNum(), status, r.priceCents()));
                }
                outRows.add(new SeatRow(String.valueOf(rg.getKey()), seats));
            }
            sections.add(new SeatSection(entry.getKey(), tier, outRows));
        }

        return new SeatMapResponse(eventId, Instant.now(), sections,
                new SeatMapResponse.Counts(available, held, sold));
    }

    private String tierFor(List<Row> anyRows) {
        // tier is derivable from price; keep it simple and consistent with seed data
        int price = anyRows.get(0).priceCents();
        return switch (price) {
            case 25_000 -> "PREMIUM";
            case 15_000 -> "MID";
            default -> "STD";
        };
    }
}
