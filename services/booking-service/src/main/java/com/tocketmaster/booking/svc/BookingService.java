package com.tocketmaster.booking.svc;

import com.tocketmaster.booking.api.PaymentFailedException;
import com.tocketmaster.booking.domain.BookingEntity;
import com.tocketmaster.booking.domain.PaymentEntity;
import com.tocketmaster.booking.domain.TicketEntity;
import com.tocketmaster.booking.dto.BookingView;
import com.tocketmaster.booking.dto.ReservationResponse;
import com.tocketmaster.booking.payment.PaymentGateway;
import com.tocketmaster.booking.repo.BookingRepository;
import com.tocketmaster.booking.repo.PaymentRepository;
import com.tocketmaster.booking.repo.TicketRepository;
import com.tocketmaster.common.api.ConflictException;
import com.tocketmaster.common.api.ForbiddenException;
import com.tocketmaster.common.api.NotFoundException;
import com.tocketmaster.common.redis.HoldManager;
import com.tocketmaster.common.redis.RedisKeys;
import com.tocketmaster.common.redis.ReservedSeatIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Booking core.
 *
 * Reservation = Redis distributed hold (SET NX PX) + short DB transaction claiming
 * tickets onto a PENDING booking. The hold TTL is the source of truth for expiry;
 * the sweeper only tidies rows for legibility and never gates correctness.
 *
 * Confirmation = charge via gateway -> webhook -> single OCC transaction
 * (UPDATE ... WHERE status='AVAILABLE') that flips tickets to SOLD. A short rowcount
 * is the final arbiter: if a hold expired mid-payment and someone else claimed the
 * seat, exactly one buyer wins and the loser is auto-refunded — no double sale, ever,
 * even with Redis fully down.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final HoldManager holds;
    private final ReservedSeatIndex reservedIndex;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration holdTtl;

    public BookingService(BookingRepository bookings,
                          TicketRepository tickets,
                          PaymentRepository payments,
                          PaymentGateway gateway,
                          HoldManager holds,
                          ReservedSeatIndex reservedIndex,
                          JdbcTemplate jdbc,
                          TransactionTemplate tx,
                          @Value("${booking.hold-ttl-seconds:600}") long holdTtlSeconds) {
        this.bookings = bookings;
        this.tickets = tickets;
        this.payments = payments;
        this.gateway = gateway;
        this.holds = holds;
        this.reservedIndex = reservedIndex;
        this.jdbc = jdbc;
        this.tx = tx;
        this.holdTtl = Duration.ofSeconds(holdTtlSeconds);
    }

    // ------------------------------------------------------------------ reserve

    public record ReserveCommand(String userId, String sessionId, UUID eventId, List<String> ticketIds) {}

    public ReservationResponse reserve(ReserveCommand cmd) {
        if (cmd.ticketIds() == null || cmd.ticketIds().isEmpty()) {
            throw new ConflictException("NO_TICKETS", "ticketIds must not be empty", null);
        }

        Boolean queueEnabled = jdbc.queryForObject(
                "select queue_enabled from events where id = ?", Boolean.class, cmd.eventId());
        if (Boolean.TRUE.equals(queueEnabled) && !isAdmitted(cmd.eventId().toString(), cmd.sessionId())) {
            throw new ForbiddenException("WAITING_ROOM_REQUIRED",
                    "This event uses a virtual waiting room; join the queue first", null);
        }

        var ticketUuids = cmd.ticketIds().stream().map(UUID::fromString).toList();

        // 1) atomic all-or-nothing Redis holds (auto-expire after TTL)
        holds.acquireAllOrNothing(cmd.ticketIds(), cmd.userId(), holdTtl);
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(holdTtl);

        try {
            // 2) validate seats belong to this event and are still AVAILABLE
            List<TicketEntity> loaded = tickets.findAllById(ticketUuids);
            if (loaded.size() != ticketUuids.size()
                    || loaded.stream().anyMatch(t -> !t.getEventId().equals(cmd.eventId()))) {
                throw new ConflictException("TICKETS_INVALID", "Tickets do not belong to event", null);
            }
            if (loaded.stream().anyMatch(t -> !"AVAILABLE".equals(t.getStatus()))) {
                throw new ConflictException("TICKETS_HELD", "Some requested seats are sold", null);
            }
            int total = loaded.stream().mapToInt(TicketEntity::getPriceCents).sum();

            // 3) short DB transaction: create PENDING booking + claim seats
            UUID bookingId = tx.execute(status -> {
                BookingEntity b = new BookingEntity();
                b.setId(UUID.randomUUID());
                b.setUserId(cmd.userId());
                b.setEventId(cmd.eventId());
                b.setStatus("PENDING");
                b.setTotalCents(total);
                b.setExpiresAt(expiresAt);
                bookings.save(b);

                int claimed = tickets.claim(b.getId(), ticketUuids);
                if (claimed != ticketUuids.size()) {
                    throw new ConflictException("TICKETS_HELD",
                            "Some requested seats are no longer available", null);
                }
                return b.getId();
            });

            // 4) publish to the seat-map overlay (scored by absolute expiry)
            for (String t : cmd.ticketIds()) {
                reservedIndex.markReserved(cmd.eventId().toString(), t, expiresAt.toInstant());
            }

            return new ReservationResponse(bookingId.toString(), "PENDING",
                    total, expiresAt, cmd.ticketIds());
        } catch (RuntimeException e) {
            rollbackHolds(cmd.eventId(), cmd.ticketIds(), cmd.userId());
            throw e;
        }
    }

    // ------------------------------------------------------------------ confirm

    public BookingView confirm(String userId, String bookingId, String cardToken) {
        BookingEntity b = bookings.findById(UUID.fromString(bookingId))
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "No booking " + bookingId));
        if (!b.getUserId().equals(userId)) {
            throw new NotFoundException("BOOKING_NOT_FOUND", "No booking " + bookingId);
        }
        if ("CONFIRMED".equals(b.getStatus())) {
            return toView(b, tickets.findByBookingId(b.getId()));
        }
        if (!"PENDING".equals(b.getStatus())) {
            throw new ConflictException("BOOKING_NOT_PENDING",
                    "Booking is " + b.getStatus(), null);
        }
        if (b.getExpiresAt().isBefore(OffsetDateTime.now())) {
            expireBooking(b.getId());
            throw new ConflictException("BOOKING_EXPIRED", "The 10-minute hold lapsed", null);
        }

        List<TicketEntity> held = tickets.findByBookingId(b.getId());
        for (TicketEntity t : held) {
            String owner = holds.ownerOf(t.getId().toString());
            if (!userId.equals(owner)) {
                expireBooking(b.getId());
                throw new ConflictException("HOLD_EXPIRED",
                        "Seat hold expired before payment completed; re-select your seats",
                        null);
            }
        }

        var intent = gateway.createIntent(b.getId(), b.getTotalCents());
        var result = gateway.charge(intent, cardToken);
        if (!result.success()) {
            throw new PaymentFailedException(result.errorCode() == null ? "PAYMENT_FAILED" : result.errorCode(),
                    "The payment was declined");
        }

        // mock gateway delivered the webhook synchronously; state is already final
        BookingEntity fresh = bookings.findById(b.getId()).orElseThrow();
        return toView(fresh, tickets.findByBookingId(fresh.getId()));
    }

    /**
     * Webhook handler (called over HTTP by the mock gateway like Stripe would).
     * Idempotent: duplicate deliveries short-circuit on payment/booking status, and
     * concurrent duplicates serialize on a pessimistic lock of the payment row.
     */
    public enum WebhookOutcome { SOLD, ALREADY_PROCESSED, SEAT_LOST_REFUNDED, BOOKING_GONE }

    @Transactional
    public WebhookOutcome onPaymentSucceeded(String providerRef) {
        var paymentOpt = payments.findWithLockByProviderRef(providerRef);
        PaymentEntity p = paymentOpt.orElseThrow(() ->
                new NotFoundException("PAYMENT_NOT_FOUND", "Unknown provider ref"));

        if ("SUCCEEDED".equals(p.getStatus()) || "REFUNDED".equals(p.getStatus())) {
            return WebhookOutcome.ALREADY_PROCESSED;
        }
        BookingEntity b = bookings.findById(p.getBookingId()).orElse(null);
        if (b == null) {
            p.setStatus("REFUNDED");
            return WebhookOutcome.BOOKING_GONE;
        }
        if ("CONFIRMED".equals(b.getStatus())) {
            p.setStatus("SUCCEEDED");
            return WebhookOutcome.ALREADY_PROCESSED;
        }
        if (!"PENDING".equals(b.getStatus())) { // cancelled/expired while paying
            p.setStatus("REFUNDED");
            cleanupHolds(b);
            return WebhookOutcome.BOOKING_GONE;
        }

        int expected = tickets.findByBookingId(b.getId()).size();
        if (expected == 0) {
            // no seats are claimed by this booking anymore — there is nothing to sell
            p.setStatus("REFUNDED");
            b.setStatus("FAILED");
            log.warn("Booking {} holds no seats; auto-refund issued", b.getId());
            return WebhookOutcome.SEAT_LOST_REFUNDED;
        }
        int sold = tickets.sellClaimedByBooking(b.getId());

        if (sold != expected) {
            // hold expired mid-payment and another buyer won the seat: auto-refund
            p.setStatus("REFUNDED");
            b.setStatus("FAILED");
            cleanupHolds(b);
            log.warn("Booking {} lost the seat race; auto-refund issued", b.getId());
            return WebhookOutcome.SEAT_LOST_REFUNDED;
        }

        b.setStatus("CONFIRMED");
        p.setStatus("SUCCEEDED");
        cleanupHolds(b);
        return WebhookOutcome.SOLD;
    }

    // ------------------------------------------------------------------ cancel / sweep

    /** Legibility sweep: flip stale PENDING rows to EXPIRED. Holds already expired via TTL. */
    @Scheduled(fixedDelayString = "${booking.sweep-interval-ms:30000}")
    public void sweep() {
        for (BookingEntity b : bookings.findExpiredPending(OffsetDateTime.now())) {
            try {
                tx.executeWithoutResult(s -> {
                    if (bookings.expireIfPending(b.getId()) == 1) {
                        tickets.releaseClaims(b.getId());
                        cleanupHolds(b);
                        log.debug("Expired booking {} swept", b.getId());
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Sweep failed for booking {}: {}", b.getId(), e.getMessage());
            }
        }
    }

    public void cancel(String userId, String bookingId) {
        BookingEntity b = bookings.findById(UUID.fromString(bookingId))
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "No booking"));
        if (!b.getUserId().equals(userId)) {
            throw new NotFoundException("BOOKING_NOT_FOUND", "No booking");
        }
        tx.executeWithoutResult(s -> {
            if (bookings.cancelIfPending(b.getId()) == 1) {
                tickets.releaseClaims(b.getId());
                cleanupHolds(b);
            }
        });
    }

    void expireBooking(UUID bookingId) {
        tx.executeWithoutResult(s -> {
            if (bookings.expireIfPending(bookingId) == 1) {
                bookings.findById(bookingId).ifPresent(b -> {
                    tickets.releaseClaims(bookingId);
                    cleanupHolds(b);
                });
            }
        });
    }

    // ------------------------------------------------------------------ queries

    public BookingView get(String bookingId) {
        BookingEntity b = bookings.findById(UUID.fromString(bookingId))
                .orElseThrow(() -> new NotFoundException("BOOKING_NOT_FOUND", "No booking"));
        return toView(b, tickets.findByBookingId(b.getId()));
    }

    public List<BookingView> forUser(String userId) {
        var out = new ArrayList<BookingView>();
        for (BookingEntity b : bookings.findByUserIdOrderByCreatedAtDesc(userId)) {
            out.add(toView(b, tickets.findByBookingId(b.getId())));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isAdmitted(String eventId, String sessionId) {
        return sessionId != null && holds.connection().sync()
                .exists(RedisKeys.admitted(eventId, sessionId)) > 0;
    }

    private void rollbackHolds(UUID eventId, List<String> ticketIds, String userId) {
        holds.releaseAll(ticketIds, userId);
        for (String t : ticketIds) {
            try {
                reservedIndex.clearReserved(eventId.toString(), t);
            } catch (RuntimeException e) {
                log.warn("ZSET cleanup failed for {} (expires lazily anyway): {}", t, e.getMessage());
            }
        }
    }

    /** Release hold keys + ZSET entries once a booking reaches a terminal state. */
    private void cleanupHolds(BookingEntity b) {
        for (TicketEntity t : tickets.findByBookingId(b.getId())) {
            try {
                holds.release(t.getId().toString(), b.getUserId());
                reservedIndex.clearReserved(b.getEventId().toString(), t.getId().toString());
            } catch (RuntimeException e) {
                log.debug("Hold cleanup raced TTL expiry for {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    private BookingView toView(BookingEntity b, List<TicketEntity> ts) {
        var seats = ts.stream()
                .map(t -> new BookingView.Seat(t.getId().toString(), t.getSection(),
                        t.getSeatRow(), t.getSeatNum(), t.getPriceCents()))
                .toList();
        return new BookingView(b.getId().toString(), b.getEventId().toString(),
                b.getStatus(), b.getTotalCents(), b.getExpiresAt(), seats);
    }
}
