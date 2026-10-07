package com.opspilot.backend.web.exception;

import com.opspilot.backend.application.exception.MonitoredServiceNotFoundException;
import com.opspilot.backend.application.exception.InsufficientOrganisationPermissionException;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.application.exception.OrganisationSlugAlreadyExistsException;
import com.opspilot.backend.domain.exception.*;
import com.opspilot.backend.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientOrganisationPermissionException.class)
    public ResponseEntity<ApiError> handleInsufficientOrganisationPermission(
            InsufficientOrganisationPermissionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ApiError(HttpStatus.FORBIDDEN.value(), e.getMessage(), Map.of())
        );
    }

    @ExceptionHandler(OrganisationSlugAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleException(
            OrganisationSlugAlreadyExistsException e) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        HttpStatus.CONFLICT.value(),
                        e.getMessage(),Map.of()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(
            MethodArgumentNotValidException e) {

        Map<String, String> errors = new HashMap<>();

        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return ResponseEntity.badRequest().body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        "Validation failed",
                        errors
                )
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException e
    ){
        return ResponseEntity.badRequest().body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        "Malformed JSON request",
                        Map.of()
                )
        );
    }

    @ExceptionHandler(InvalidOrganisationNameException.class)
    public ResponseEntity<ApiError> handleInvalidOrganisationNameException(
            InvalidOrganisationNameException e) {
        return ResponseEntity.badRequest().body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(InvalidOrganisationSlugException.class)
    public ResponseEntity<ApiError> handleInvalidOrganisationSlugException(
            InvalidOrganisationSlugException e){
        return ResponseEntity.badRequest().body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(OrganisationAlreadyInStatusException.class)
    public ResponseEntity<ApiError> handleOrganisationAlreadyInStatusException(
            OrganisationAlreadyInStatusException e){
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiError(
                        HttpStatus.CONFLICT.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(OrganisationNotFoundException.class)
    public ResponseEntity<ApiError> handleOrganisationNotFoundException(
            OrganisationNotFoundException e){
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(
                        HttpStatus.NOT_FOUND.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(MonitoredServiceNotFoundException.class)
    public ResponseEntity<ApiError> handleMonitoredServiceNotFoundException(
            MonitoredServiceNotFoundException e) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(
                        HttpStatus.NOT_FOUND.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(MonitoredServiceAlreadyInStatusException.class)
    public ResponseEntity<ApiError> handleMonitoredServiceAlreadyInStatusException(
            MonitoredServiceAlreadyInStatusException e) {

        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiError(
                        HttpStatus.CONFLICT.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(InvalidMonitoredServiceOwnerException.class)
    public ResponseEntity<ApiError> handleInvalidMonitoredServiceOwnerException(
            InvalidMonitoredServiceOwnerException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(InvalidMonitoredServiceBaseUrlException.class)
    public ResponseEntity<ApiError> handleInvalidMonitoredServiceBaseUrlException(
            InvalidMonitoredServiceBaseUrlException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(InvalidMonitoredServiceNameException.class)
    public ResponseEntity<ApiError> handleInvalidMonitoredServiceNameException(
            InvalidMonitoredServiceNameException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }

    @ExceptionHandler(InvalidMonitoredServiceHealthEndpointException.class)
    public ResponseEntity<ApiError> handleInvalidMonitoredServiceHealthEndpointException(
            InvalidMonitoredServiceHealthEndpointException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiError(
                        HttpStatus.BAD_REQUEST.value(),
                        e.getMessage(),
                        Map.of()
                )
        );
    }
}
