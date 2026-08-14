package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.Environment;
import com.opspilot.backend.domain.ServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


import com.opspilot.backend.domain.Environment;
import com.opspilot.backend.domain.ServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMonitoredServiceRequest(

        @NotBlank
        @Size(max = 120)
        String name,

        @NotNull
        ServiceType serviceType,

        @NotNull
        Environment environment,

        @NotBlank
        @Size(max = 2048)
        String baseUrl,

        @NotBlank
        @Size(max = 2048)
        String healthEndpoint,

        @NotBlank
        @Size(max = 100)
        String owner
) {
}

