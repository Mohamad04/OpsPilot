package com.opspilot.backend.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.util.List;

@Validated
@ConfigurationProperties("opspilot.monitoring")
public record MonitoringProperties(
        @Min(1000) @Max(86400000) long intervalMs,
        @Min(0) @Max(86400000) long initialDelayMs,
        @Min(100) @Max(10000) int connectTimeoutMs,
        @Min(100) @Max(30000) int requestTimeoutMs,
        @Min(1) @Max(16) int workers,
        @NotEmpty List<@Min(1) @Max(65535) Integer> allowedPorts) {
    @AssertTrue(message = "connect-timeout-ms must not exceed request-timeout-ms")
    public boolean isTimeoutOrderValid() { return connectTimeoutMs <= requestTimeoutMs; }
}
