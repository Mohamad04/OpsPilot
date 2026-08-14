package com.opspilot.backend.domain.exception;

public class MonitoredServiceAlreadyInStatusException extends IllegalStateException {
    public MonitoredServiceAlreadyInStatusException(boolean enabled) {
        super(enabled ? "Monitored Service is already enabled" : "Monitored Service is already in status");
    }
}
