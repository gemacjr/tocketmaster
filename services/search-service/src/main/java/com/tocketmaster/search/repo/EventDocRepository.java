package com.tocketmaster.search.repo;

import com.tocketmaster.search.dto.SearchHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class EventDocRepository {

    private final JdbcTemplate jdbc;

    public EventDocRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DocRow(String eventId, String name, String description, String category,
                         OffsetDateTime startsAt, String performerName, String venueName, String city) {}

    /** All upcoming events, for (re)indexing. */
    public List<DocRow> allUpcoming() {
        return jdbc.query("""
                        SELECT e.id::TEXT, e.name, e.description, e.category,
                               e.starts_at AS starts_at_raw, p.name AS performer_name,
                               v.name AS venue_name, v.city
                        FROM events e
                        JOIN performers p ON p.id = e.performer_id
                        JOIN venues v ON v.id = e.venue_id
                        WHERE e.starts_at > now()
                        ORDER BY e.starts_at
                        """,
                (rs, i) -> new DocRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        toOdt(rs.getTimestamp("starts_at_raw")),
                        rs.getString("performer_name"), rs.getString("venue_name"), rs.getString("city")));
    }

    /**
     * Degraded-mode search straight off PostgreSQL when OpenSearch is unavailable.
     * ILIKE scans are exactly the slow path the guide warns about — acceptable only
     * as an availability fallback for the read path.
     */
    public List<SearchHit> fallbackSearch(String q, String category, int limit) {
        String like = "%" + (q == null ? "" : q) + "%";
        return jdbc.query("""
                        SELECT e.id::TEXT, e.name, e.description, e.category,
                               e.starts_at AS starts_at_raw,
                               p.name AS performer_name, v.name AS venue_name, v.city
                        FROM events e
                        JOIN performers p ON p.id = e.performer_id
                        JOIN venues v ON v.id = e.venue_id
                        WHERE e.starts_at > now()
                          AND (e.name ILIKE ? OR p.name ILIKE ? OR v.city ILIKE ?)
                          AND (? IS NULL OR e.category = ?)
                        ORDER BY e.starts_at
                        LIMIT ?
                        """,
                ps -> {
                    ps.setString(1, like);
                    ps.setString(2, like);
                    ps.setString(3, like);
                    ps.setString(4, blankToNull(category));
                    ps.setString(5, category == null || category.isBlank()
                            ? null : category.toUpperCase());
                    ps.setInt(6, limit);
                },
                (rs, i) -> new SearchHit(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        toOdt(rs.getTimestamp("starts_at_raw")),
                        rs.getString("performer_name"), rs.getString("venue_name"), rs.getString("city")));
    }

    public record EventCity(String eventId, String city) {}

    /** City lookup used by reindexing. */
    public List<EventCity> cities() {
        return jdbc.query(
                "SELECT e.id::TEXT, v.city FROM events e JOIN venues v ON v.id = e.venue_id " +
                        "WHERE e.starts_at > now()",
                (rs, i) -> new EventCity(rs.getString(1), rs.getString(2)));
    }

    private static OffsetDateTime toOdt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
