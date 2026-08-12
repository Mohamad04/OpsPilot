package com.opspilot.backend.application.exception;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
@ExceptionHandler(OrganisationSlugAlreadyExistsException.class)
    public String handleException(OrganisationSlugAlreadyExistsException e) {
    return e.getMessage();
    }
}
