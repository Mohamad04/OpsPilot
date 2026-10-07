package com.opspilot.backend.application.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class OrganisationMembershipNotFoundException extends RuntimeException {

    public OrganisationMembershipNotFoundException(String message) {
        super(message);
    }
}