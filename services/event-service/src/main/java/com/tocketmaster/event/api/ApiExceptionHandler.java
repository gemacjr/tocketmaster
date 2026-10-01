package com.tocketmaster.event.api;

import com.tocketmaster.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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
