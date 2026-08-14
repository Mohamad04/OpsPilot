package com.opspilot.backend.domain.exception;

public class InvalidMonitoredServiceBaseUrlException extends IllegalArgumentException {
    public InvalidMonitoredServiceBaseUrlException(String message) {
        super(message);
    }
}
