package com.opspilot.backend.web;

import com.opspilot.backend.application.AlertRuleService;
import com.opspilot.backend.web.dto.AlertRuleResponse;
import com.opspilot.backend.web.dto.ConfigureAlertRuleRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/organisations/{organisationId}/monitored-services/{monitoredServiceId}/alert-rule")
public class AlertRuleController {
    private final AlertRuleService rules;

    public AlertRuleController(AlertRuleService rules) { this.rules = rules; }

    @GetMapping
    public AlertRuleResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
                                  @PathVariable UUID monitoredServiceId) {
        return rules.getRule(jwt.getSubject(), organisationId, monitoredServiceId);
    }

    @PutMapping
    public AlertRuleResponse put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
                                  @PathVariable UUID monitoredServiceId,
                                  @Valid @RequestBody ConfigureAlertRuleRequest request) {
        return rules.configureRule(jwt.getSubject(), organisationId, monitoredServiceId, request.enabled(),
                request.consecutiveFailures(), request.consecutiveRecoveries(), request.severity());
    }
}
