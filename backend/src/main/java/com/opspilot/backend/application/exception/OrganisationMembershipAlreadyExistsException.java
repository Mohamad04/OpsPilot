package com.opspilot.backend.application.exception;

public class OrganisationMembershipAlreadyExistsException extends RuntimeException {
    public OrganisationMembershipAlreadyExistsException(String message) {
        super(message);
    }
}
