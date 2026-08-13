package com.opspilot.backend.domain.exception;

import com.opspilot.backend.domain.OrganisationStatus;

public class OrganisationAlreadyInStatusException extends IllegalStateException {
    public OrganisationAlreadyInStatusException(OrganisationStatus organisationStatus) {
        super("Organisation status is already "+organisationStatus);
    }
}
