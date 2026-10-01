package com.tocketmaster.common.api;

/**
 * Base class for API errors carrying a machine-readable code and HTTP status.
 */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final transient Object details;

    protected ApiException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }

    public abstract int httpStatus();
}
