package com.opspilot.backend.domain.exception;

import com.opspilot.backend.domain.IncidentStatus;

public class InvalidIncidentTransitionException extends RuntimeException {
    public InvalidIncidentTransitionException(IncidentStatus from, IncidentStatus to) {
        super("Incident transition from " + from + " to " + to + " is not allowed");
    }
}
