package com.tocketmaster.common.api;

/** 403 — authenticated but not allowed to do this yet (e.g. waiting-room not admitted). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String code, String message, Object details) {
        super(code, message, details);
    }

    @Override
    public int httpStatus() {
        return 403;
    }
}
