package com.tocketmaster.booking.payment;

import com.tocketmaster.booking.domain.PaymentEntity;
import com.tocketmaster.booking.repo.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Mock Stripe. Behavior:
 *  - tokens starting with "tok_fail" decline (card_declined)
 *  - any other token succeeds, then the gateway POSTs payment.succeeded to our
 *    /api/payments/webhook endpoint exactly like Stripe's async callback would —
 *    exercising the real idempotent webhook path end-to-end.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final PaymentRepository payments;
    private final RestClient restClient;

    public MockPaymentGateway(PaymentRepository payments,
                              @Value("${app.self-url}") String selfUrl) {
        this.payments = payments;
        this.restClient = RestClient.builder().baseUrl(selfUrl).build();
    }

    @Override
    public Intent createIntent(UUID bookingId, int amountCents) {
        // idempotency: one intent per booking; reuse on retries
        PaymentEntity existing = payments.findByIdempotencyKey(bookingId.toString()).orElse(null);
        if (existing != null) {
            return new Intent(existing.getProviderRef(), bookingId, existing.getAmountCents());
        }

        PaymentEntity p = new PaymentEntity();
        p.setId(UUID.randomUUID());
        p.setBookingId(bookingId);
        p.setProvider("mock-stripe");
        p.setProviderRef("pi_" + UUID.randomUUID());
        p.setIdempotencyKey(bookingId.toString());
        p.setAmountCents(amountCents);
        p.setStatus("REQUIRES_CONFIRMATION");
        payments.save(p);
        return new Intent(p.getProviderRef(), bookingId, amountCents);
    }

    @Override
    public ChargeResult charge(Intent intent, String cardToken) {
        if (cardToken == null || !cardToken.startsWith("tok_")) {
            return ChargeResult.fail("invalid_token");
        }
        if (cardToken.startsWith("tok_fail")) {
            log.info("Mock charge declined for {} ({})", intent.paymentRef(), cardToken);
            return ChargeResult.fail("card_declined");
        }

        // simulate processor latency, then deliver webhook like Stripe would
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var body = Map.of(
                "type", "payment.succeeded",
                "data", Map.of("providerRef", intent.paymentRef()));
        restClient.post()
                .uri("/api/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        return ChargeResult.ok();
    }
}
