package com.opspilot.backend.domain.exception;

public class InvalidMonitoredServiceNameException extends IllegalArgumentException {
    public InvalidMonitoredServiceNameException(String message) {
        super(message);
    }
}
