package com.opspilot.backend.domain.exception;

public class InvalidOrganisationNameException extends IllegalArgumentException {
    public InvalidOrganisationNameException(String message) {
        super(message);
    }
}
