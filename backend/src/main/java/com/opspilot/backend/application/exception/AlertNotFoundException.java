package com.opspilot.backend.application.exception;

public class AlertNotFoundException extends RuntimeException {
    public AlertNotFoundException() { super("Alert not found"); }
}
