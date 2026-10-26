package com.opspilot.backend.web;

import com.opspilot.backend.application.AlertQuery;
import com.opspilot.backend.application.AlertQueryService;
import com.opspilot.backend.domain.AlertSeverity;
import com.opspilot.backend.domain.AlertStatus;
import com.opspilot.backend.web.dto.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/organisations/{organisationId}")
public class AlertController {
    private final AlertQueryService alerts;
    public AlertController(AlertQueryService alerts) { this.alerts = alerts; }

    @GetMapping("/alerts")
    public AlertPageResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @RequestParam(required = false) AlertStatus status, @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return alerts.list(jwt.getSubject(), organisationId, new AlertQuery(status, severity, serviceId, from, to, page, size));
    }

    @GetMapping("/alerts/summary")
    public AlertSummaryResponse summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId) {
        return alerts.summary(jwt.getSubject(), organisationId);
    }

    @GetMapping("/alerts/{alertId}")
    public AlertResponse details(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId, @PathVariable UUID alertId) {
        return alerts.details(jwt.getSubject(), organisationId, alertId);
    }

    @GetMapping("/monitored-services/{serviceId}/alerts")
    public AlertPageResponse serviceHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @PathVariable UUID serviceId,
            @RequestParam(required = false) AlertStatus status, @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return alerts.list(jwt.getSubject(), organisationId, new AlertQuery(status, severity, serviceId, from, to, page, size));
    }
}
