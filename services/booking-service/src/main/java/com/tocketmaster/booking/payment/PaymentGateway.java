package com.tocketmaster.booking.payment;

import java.util.UUID;

/**
 * Seam for the payment provider. The guide uses Stripe; this mock implements the same
 * shape (create intent -> charge -> async webhook callback) so swapping in the real
 * Stripe client touches only this package. Card data never touches our servers: the
 * client sends a token, exactly like Stripe.js.
 */
public interface PaymentGateway {

    record Intent(String paymentRef, UUID bookingId, int amountCents) {}

    record ChargeResult(boolean success, String errorCode) {
        public static ChargeResult ok() { return new ChargeResult(true, null); }
        public static ChargeResult fail(String code) { return new ChargeResult(false, code); }
    }

    /** Persist a REQUIRES_CONFIRMATION payment row keyed by idempotencyKey (= bookingId). */
    Intent createIntent(UUID bookingId, int amountCents);

    /**
     * Charge a tokenized card. On success the gateway delivers
     * payment.succeeded to our webhook asynchronously (mock: immediate self-call).
     */
    ChargeResult charge(Intent intent, String cardToken);
}
