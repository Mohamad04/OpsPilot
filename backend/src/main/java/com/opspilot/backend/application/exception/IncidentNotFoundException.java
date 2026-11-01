package com.opspilot.backend.application.exception;

public class IncidentNotFoundException extends RuntimeException {
    public IncidentNotFoundException() { super("Incident not found"); }
}
