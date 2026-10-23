package com.opspilot.backend.application.exception;

public class UserProvisioningConflictException extends RuntimeException {
    public UserProvisioningConflictException() {
        super("Unable to provision this identity. Contact an administrator to resolve the account conflict.");
    }
}
