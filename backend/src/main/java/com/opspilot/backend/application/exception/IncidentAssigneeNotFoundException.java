package com.opspilot.backend.application.exception;

public class IncidentAssigneeNotFoundException extends RuntimeException {
    public IncidentAssigneeNotFoundException() { super("Eligible assignee not found in this organisation"); }
}
