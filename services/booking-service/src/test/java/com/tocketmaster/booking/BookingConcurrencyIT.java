package com.tocketmaster.booking;

import com.tocketmaster.booking.domain.PaymentEntity;
import com.tocketmaster.booking.dto.ReservationResponse;
import com.tocketmaster.booking.payment.PaymentGateway;
import com.tocketmaster.booking.payment.MockPaymentGateway;
import com.tocketmaster.booking.repo.PaymentRepository;
import com.tocketmaster.booking.svc.BookingService;
import com.tocketmaster.common.api.ApiException;
import com.tocketmaster.common.redis.HoldManager;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingConcurrencyIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // static init guarantees containers exist before Spring resolves properties
    static {
        POSTGRES.withCopyFileToContainer(
                        MountableFile.forClasspathResource("schema.sql"),
                        "/docker-entrypoint-initdb.d/01-schema.sql")
                .start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("redis.uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("booking.hold-ttl-seconds", () -> 2); // fast expiry-driven behavior
        registry.add("app.self-url", () -> "http://localhost:1"); // webhook self-calls disabled in tests
    }

    @Autowired BookingService bookingService;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired HoldManager holds;

    /**
     * Test-only gateway: identical to the mock Stripe but delivers the webhook
     * in-process instead of over HTTP (no server self-call inside the test JVM).
     */
    @TestConfiguration
    static class InlineWebhookGateway {
        @Bean
        @Primary
        PaymentGateway inlineMockGateway(PaymentRepository payments, ObjectProvider<BookingService> svc) {
            return new MockPaymentGateway(payments, "unused") {
                @Override
                public ChargeResult charge(Intent intent, String cardToken) {
                    if (cardToken == null || !cardToken.startsWith("tok_")) {
                        return ChargeResult.fail("invalid_token");
                    }
                    if (cardToken.startsWith("tok_fail")) {
                        return ChargeResult.fail("card_declined");
                    }
                    var outcome = svc.getObject().onPaymentSucceeded(intent.paymentRef());
                    return switch (outcome) {
                        case SOLD, ALREADY_PROCESSED -> ChargeResult.ok();
                        default -> ChargeResult.fail("seat_lost_refunded");
                    };
                }
            };
        }
    }

    // static: JUnit creates a fresh test instance per method; the scenario chain
    // (hot sale -> expiry reclaim -> OCC race -> idempotency) shares one seeded event.
    static String eventId;
    static String ticketId;

    /** One event with exactly one ticket — the contended resource. Seeded once. */
    void seedSingleTicketEvent() {
        if (ticketId != null) {
            return;
        }
        jdbc.update("INSERT INTO venues (id,name,city,country,capacity,seat_map) VALUES (?,'T','TC','US',1,'{}')",
                UUID.randomUUID());
        UUID performerId = UUID.randomUUID();
        jdbc.update("INSERT INTO performers (id,name,category) VALUES (?,'P','CONCERT')", performerId);
        eventId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO events (id, name, category, performer_id, venue_id, starts_at)
            VALUES (?, 'Test Event', 'CONCERT', ?,
                    (SELECT id FROM venues LIMIT 1), now() + interval '30 days')
            """, UUID.fromString(eventId), performerId);
        jdbc.update("INSERT INTO tickets (event_id, section, seat_row, seat_num, price_cents) " +
                "VALUES (?, 'A','1',1,10000)", UUID.fromString(eventId));
        ticketId = jdbc.queryForObject(
                "SELECT id::TEXT FROM tickets WHERE event_id=?", String.class, UUID.fromString(eventId));
    }

    @Test
    @Order(1)
    void onlyOneBuyerWinsAHotTicketUnderParallelReservations() throws Exception {
        seedSingleTicketEvent();

        int buyers = 25;
        var results = new ConcurrentHashMap<Integer, ReservationResponse>();
        var conflicts = new AtomicInteger();
        var latch = new CountDownLatch(buyers);
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        var startGate = new CountDownLatch(1);

        for (int i = 0; i < buyers; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    results.put(n, bookingService.reserve(new BookingService.ReserveCommand(
                            "user-" + n, "sess-" + n, UUID.fromString(eventId), List.of(ticketId))));
                } catch (Exception e) {
                    conflicts.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // exactly one winner; everyone else got a clean conflict
        assertThat(results).hasSize(1);
        assertThat(conflicts.get()).isEqualTo(buyers - 1);

        // DB agrees: one PENDING booking holds the single ticket
        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE status='PENDING'", Integer.class);
        assertThat(pending).isEqualTo(1);
        String claimedBy = jdbc.queryForObject(
                "SELECT booking_id::TEXT FROM tickets WHERE id=?", String.class, UUID.fromString(ticketId));
        assertThat(claimedBy).isEqualTo(results.values().iterator().next().bookingId());

        // Redis agrees: exactly one live hold owned by the winner
        assertThat(holds.ownerOf(ticketId)).isEqualTo(
                "user-" + results.keySet().iterator().next());
    }

    @Test
    @Order(2)
    void seatFreesUpExactlyWhenHoldExpiresAndNextBuyerClaimsIt() throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT b.id::TEXT AS bid, b.user_id AS uid FROM bookings b WHERE b.status='PENDING' LIMIT 1");
        String firstBookingId = (String) row.get("bid");
        String firstUser = (String) row.get("uid");

        // wait past TTL: hold key evaporates, stale claim becomes re-claimable
        await().atMost(6, TimeUnit.SECONDS)
                .until(() -> holds.ownerOf(ticketId) == null);

        // second buyer reserves the same seat without any sweeper having run
        ReservationResponse second = bookingService.reserve(new BookingService.ReserveCommand(
                "user-second", "sess-second", UUID.fromString(eventId), List.of(ticketId)));

        // original buyer's payment attempt is rejected: their hold expired
        try {
            bookingService.confirm(firstUser, firstBookingId, "tok_visa");
            throw new AssertionError("expected HOLD_EXPIRED");
        } catch (ApiException e) {
            assertThat(e.getCode()).isIn("HOLD_EXPIRED", "BOOKING_EXPIRED");
        }

        // the new holder confirms successfully and the ticket is SOLD once
        var confirmed = bookingService.confirm("user-second", second.bookingId(), "tok_visa");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");

        Integer soldCount = jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE id=? AND status='SOLD'",
                Integer.class, UUID.fromString(ticketId));
        assertThat(soldCount).isEqualTo(1);
    }

    @Test
    @Order(3)
    void occFinalizationRefundsTheLoserWhenSeatWasLostMidPayment() {
        // Fabricate the guide's edge case: two PENDING bookings both believe they own
        // the same ticket (only possible if a hold expired mid-payment), then both
        // webhooks arrive. OCC must crown exactly one winner and refund the loser.
        String eId = jdbc.queryForObject("SELECT id::TEXT FROM events WHERE name='Test Event'", String.class);

        // resurrect the previous booking to PENDING and free the seat while keeping the stale claim
        jdbc.update("""
                UPDATE bookings SET status='PENDING', expires_at = now() + interval '10 minutes'
                WHERE id = (SELECT booking_id FROM tickets WHERE id = ? AND booking_id IS NOT NULL)
                """, UUID.fromString(ticketId));
        jdbc.update("UPDATE tickets SET status='AVAILABLE' WHERE id=?", UUID.fromString(ticketId));

        String bookingA = jdbc.queryForObject(
                "SELECT booking_id::TEXT FROM tickets WHERE id=?",
                String.class, UUID.fromString(ticketId));

        // booking B claims the same (claimed-but-AVAILABLE) ticket via raw SQL
        String bookingB = jdbc.queryForObject(
                "INSERT INTO bookings (user_id, event_id, status, total_cents, expires_at) " +
                "VALUES ('user-B', ?, 'PENDING', 10000, now() + interval '10 minutes') RETURNING id::TEXT",
                String.class, UUID.fromString(eId));
        jdbc.update("UPDATE tickets SET booking_id=? WHERE id=?",
                UUID.fromString(bookingB), UUID.fromString(ticketId));

        PaymentEntity payA = intentFor(UUID.fromString(bookingA));
        PaymentEntity payB = intentFor(UUID.fromString(bookingB));
        payments.save(payA);
        payments.save(payB);

        var outcomeA = bookingService.onPaymentSucceeded(payA.getProviderRef());
        var outcomeB = bookingService.onPaymentSucceeded(payB.getProviderRef());

        // exactly one SOLD outcome; the other is a refund/idempotent short-circuit
        var outcomes = List.of(outcomeA, outcomeB);
        assertThat(outcomes.stream().filter(o -> o == BookingService.WebhookOutcome.SOLD).count())
                .isEqualTo(1);

        Integer soldRows = jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE id=? AND status='SOLD'",
                Integer.class, UUID.fromString(ticketId));
        assertThat(soldRows).isEqualTo(1);

        Integer confirmedBookings = jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE status='CONFIRMED'", Integer.class);
        assertThat(confirmedBookings).isEqualTo(1);
    }

    @Test
    @Order(4)
    void duplicateWebhooksAreIdempotent() {
        String ref = jdbc.queryForObject(
                "SELECT provider_ref FROM payments ORDER BY created_at DESC LIMIT 1", String.class);

        var outcome = bookingService.onPaymentSucceeded(ref);
        assertThat(outcome).isNotEqualTo(BookingService.WebhookOutcome.SOLD);

        // parallel duplicates also collapse without state change
        ExecutorService pool = Executors.newFixedThreadPool(8);
        var latch = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    bookingService.onPaymentSucceeded(ref);
                } catch (Exception ignored) {
                    // terminal states surface as 409s over HTTP; here we just drain them
                } finally {
                    latch.countDown();
                }
            });
        }
        await().atMost(10, TimeUnit.SECONDS).until(() -> latch.getCount() == 0);
        pool.shutdown();

        Integer confirmed = jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE status='CONFIRMED'", Integer.class);
        assertThat(confirmed).isEqualTo(1);
    }

    private PaymentEntity intentFor(UUID bookingId) {
        PaymentEntity p = new PaymentEntity();
        p.setId(UUID.randomUUID());
        p.setBookingId(bookingId);
        p.setProvider("mock-stripe");
        p.setProviderRef("pi_" + UUID.randomUUID());
        p.setIdempotencyKey("it-" + UUID.randomUUID()); // distinct keys: two intents, same booking
        p.setAmountCents(10000);
        p.setStatus("REQUIRES_CONFIRMATION");
        return p;
    }
}
