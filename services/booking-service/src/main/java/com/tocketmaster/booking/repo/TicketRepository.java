package com.tocketmaster.booking.repo;

import com.tocketmaster.booking.domain.TicketEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<TicketEntity, UUID> {

    List<TicketEntity> findByBookingId(UUID bookingId);

    /**
     * Claim seats for a pending reservation. Precondition on status makes this safe
     * under concurrency; rowcount tells us whether we won everything.
     *
     * Seats claimed by an EXPIRED pending booking are re-claimable immediately — the
     * EXISTS clause recognizes stale claims by their expires_at, so a seat frees up
     * the instant its Redis hold TTL lapses (no sweeper dependency on the hot path).
     */
    @Modifying
    @Query(value = """
            UPDATE tickets t
            SET booking_id = :bookingId, version = version + 1
            WHERE t.id IN (:ticketIds)
              AND t.status = 'AVAILABLE'
              AND (
                    t.booking_id IS NULL
                 OR EXISTS (SELECT 1 FROM bookings b
                            WHERE b.id = t.booking_id
                              AND b.status = 'PENDING'
                              AND b.expires_at < now())
                  )
            """, nativeQuery = true)
    int claim(@Param("bookingId") UUID bookingId, @Param("ticketIds") List<UUID> ticketIds);

    /**
     * OCC finalization: only rows still AVAILABLE flip to SOLD. If someone else sold a
     * seat between hold expiry and confirmation, the rowcount comes up short and the
     * caller issues the auto-refund — exactly one buyer can ever win a seat.
     */
    @Modifying
    @Query("""
            update TicketEntity t
            set t.status = 'SOLD', t.version = t.version + 1
            where t.bookingId = :bookingId and t.status = 'AVAILABLE'
            """)
    int sellClaimedByBooking(UUID bookingId);

    /** Release claims when a pending booking is cancelled/expires without sale. */
    @Modifying
    @Query("""
            update TicketEntity t
            set t.bookingId = null, t.version = t.version + 1
            where t.bookingId = :bookingId and t.status = 'AVAILABLE'
            """)
    int releaseClaims(UUID bookingId);
}
