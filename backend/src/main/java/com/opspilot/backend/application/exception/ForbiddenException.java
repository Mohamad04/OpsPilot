package com.opspilot.backend.application.exception;

public class ForbiddenException extends RuntimeException{
    public ForbiddenException(String message){
        super(message);
    }
}
