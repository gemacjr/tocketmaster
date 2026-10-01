package com.tocketmaster.common.api;

/**
 * 404-class failures: unknown event, booking, etc.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(code, message, null);
    }

    @Override
    public int httpStatus() {
        return 404;
    }
}
