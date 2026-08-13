package com.opspilot.backend.domain.exception;

public class InvalidOrganisationSlugException extends IllegalArgumentException {
    public InvalidOrganisationSlugException(String message) {
        super(message);
    }
}
