package com.opspilot.backend.domain.exception;

public class InvalidMonitoredServiceOwnerException extends IllegalArgumentException {
    public InvalidMonitoredServiceOwnerException(String message) {
        super(message);
    }
}
