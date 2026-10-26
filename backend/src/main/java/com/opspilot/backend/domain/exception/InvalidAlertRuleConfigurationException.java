package com.opspilot.backend.domain.exception;

public class InvalidAlertRuleConfigurationException extends RuntimeException {
    public InvalidAlertRuleConfigurationException(String message) {
        super(message);
    }
}
