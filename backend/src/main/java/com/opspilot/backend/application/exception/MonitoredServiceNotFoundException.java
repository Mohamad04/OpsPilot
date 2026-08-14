package com.opspilot.backend.application.exception;

import java.util.UUID;

public class MonitoredServiceNotFoundException extends RuntimeException {

    public MonitoredServiceNotFoundException(UUID id) {
        super("Monitored service with id %s was not found".formatted(id));
    }
}
