package com.opspilot.backend.domain.exception;

import com.opspilot.backend.domain.OrganisationStatus;

public class OrganisationStatusException extends IllegalStateException {
    public OrganisationStatusException(OrganisationStatus organisationStatus) {
        super("Organisation status is already "+organisationStatus.toString());
    }
}
