package com.opspilot.backend.application.exception;



public class OrganisationSlugAlreadyExistsException extends RuntimeException {
    public OrganisationSlugAlreadyExistsException(String slug) {
        super("An organisation with slug '" + slug + "' already exists");
    }
}
