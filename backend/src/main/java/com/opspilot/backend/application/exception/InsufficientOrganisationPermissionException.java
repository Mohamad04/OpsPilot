package com.opspilot.backend.application.exception;

public class InsufficientOrganisationPermissionException extends RuntimeException {

    public InsufficientOrganisationPermissionException(String message) {
        super(message);
    }
}