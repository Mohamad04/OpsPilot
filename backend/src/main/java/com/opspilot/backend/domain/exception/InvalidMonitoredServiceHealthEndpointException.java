package com.opspilot.backend.domain.exception;

public class InvalidMonitoredServiceHealthEndpointException extends IllegalArgumentException {
    public InvalidMonitoredServiceHealthEndpointException(String message) {
        super(message);
    }
}
