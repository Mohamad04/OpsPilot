package com.opspilot.backend.web;

import com.opspilot.backend.application.HealthResultService;
import com.opspilot.backend.web.dto.HealthCheckResponse;
import com.opspilot.backend.web.dto.LatestHealthResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/health")
public class HealthCheckController {
    private final HealthResultService results;
    public HealthCheckController(HealthResultService results) { this.results = results; }

    @GetMapping("/latest")
    public LatestHealthResponse latest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId, @PathVariable UUID monitoredServiceId) {
        return results.latest(jwt.getSubject(), organisationId, monitoredServiceId);
    }

    @GetMapping("/history")
    public List<HealthCheckResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
                                             @PathVariable UUID monitoredServiceId, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return results.history(jwt.getSubject(), organisationId, monitoredServiceId, limit);
    }
}
