package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.AlertSeverity;
import java.util.Map;

public record AlertSummaryResponse(long totalAlerts, long activeAlerts, long resolvedAlerts,
                                   Map<AlertSeverity, Long> activeBySeverity) {
    public AlertSummaryResponse { activeBySeverity = Map.copyOf(activeBySeverity); }
}
