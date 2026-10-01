package com.tocketmaster.booking.api;

import com.tocketmaster.booking.dto.BookingView;
import com.tocketmaster.booking.dto.ReservationResponse;
import com.tocketmaster.booking.svc.BookingService;
import com.tocketmaster.common.api.ApiException;
import com.tocketmaster.common.api.ConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    public record ReserveBody(@NotNull UUID eventId, @NotEmpty List<String> ticketIds) {}

    /**
     * POST /api/bookings/reservations — hold seats for 10 minutes.
     * X-User-Id identifies the buyer; X-Session-Id is the waiting-room session.
     */
    @PostMapping("/bookings/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @Valid @RequestBody ReserveBody body) {
        return bookingService.reserve(new BookingService.ReserveCommand(
                userId, sessionId, body.eventId(), body.ticketIds()));
    }

    public record ConfirmBody(@NotBlank String cardToken) {}

    /** POST /api/bookings/{id}/confirm — charge + finalize (OCC-guarded). */
    @PostMapping("/bookings/{bookingId}/confirm")
    public BookingView confirm(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @PathVariable String bookingId,
            @Valid @RequestBody ConfirmBody body) {
        return bookingService.confirm(userId, bookingId, body.cardToken());
    }

    /** POST /api/bookings/{id}/cancel — release holds early. */
    @PostMapping("/bookings/{bookingId}/cancel")
    public void cancel(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId,
            @PathVariable String bookingId) {
        bookingService.cancel(userId, bookingId);
    }

    @GetMapping("/bookings/{bookingId}")
    public BookingView get(@PathVariable String bookingId) {
        return bookingService.get(bookingId);
    }

    /** GET /api/users/me/bookings — below-the-line nicety. */
    @GetMapping("/users/me/bookings")
    public List<BookingView> myBookings(
            @RequestHeader(value = "X-User-Id", defaultValue = "guest") String userId) {
        return bookingService.forUser(userId);
    }

    // ------------------------------------------------------------------ payments webhook

    public record WebhookEvent(String type, Data data) {
        public record Data(String providerRef) {}
    }

    /**
     * POST /api/payments/webhook — payment processor callback (mock Stripe).
     * Idempotent by providerRef; safe under duplicate/retried deliveries.
     */
    @PostMapping("/payments/webhook")
    public Map<String, String> webhook(@RequestBody WebhookEvent event) {
        if (!"payment.succeeded".equals(event.type())) {
            return Map.of("ignored", "true");
        }
        var outcome = bookingService.onPaymentSucceeded(event.data().providerRef());
        if (outcome == BookingService.WebhookOutcome.SEAT_LOST_REFUNDED) {
            throw new ConflictException("SEAT_LOST", "Seat sold during checkout; refunded",
                    Map.of("outcome", outcome.name()));
        }
        if (outcome == BookingService.WebhookOutcome.BOOKING_GONE) {
            throw new ConflictException("BOOKING_GONE", "Booking no longer pending; refunded",
                    Map.of("outcome", outcome.name()));
        }
        return Map.of("outcome", outcome.name());
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.valueOf(e.httpStatus()));
        pd.setTitle(e.getCode());
        pd.setDetail(e.getMessage());
        if (e.getDetails() != null) {
            pd.setProperty("code", e.getCode());
            pd.setProperty("details", e.getDetails());
        }
        return pd;
    }
}
