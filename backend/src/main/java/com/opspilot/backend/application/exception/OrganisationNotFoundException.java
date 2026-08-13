package com.opspilot.backend.application.exception;

import java.util.UUID;

public class OrganisationNotFoundException extends RuntimeException {
    public OrganisationNotFoundException(UUID id) {
        super("organisation with the id %s is not found".formatted(id));
    }
}
