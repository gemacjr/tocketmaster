package com.tocketmaster.booking.api;

import com.tocketmaster.common.api.ApiException;

/**
 * 402-class failure: the processor declined the charge. Booking stays PENDING until
 * its hold TTL lapses so the buyer can retry with another card.
 */
public class PaymentFailedException extends ApiException {

    public PaymentFailedException(String code, String message) {
        super(code, message, null);
    }

    @Override
    public int httpStatus() {
        return 402;
    }
}
