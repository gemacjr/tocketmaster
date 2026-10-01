package com.tocketmaster.common.api;

/**
 * 409-class failures: seats already held/sold, expired holds, unadmitted users.
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message, Object details) {
        super(code, message, details);
    }

    @Override
    public int httpStatus() {
        return 409;
    }
}
