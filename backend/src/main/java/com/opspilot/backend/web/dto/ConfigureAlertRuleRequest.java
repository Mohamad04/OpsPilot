package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.AlertRule;
import com.opspilot.backend.domain.AlertSeverity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ConfigureAlertRuleRequest(
        @NotNull @JsonDeserialize(using = BooleanValue.class) Boolean enabled,
        @NotNull @Min(AlertRule.MIN_THRESHOLD) @Max(AlertRule.MAX_THRESHOLD)
        @JsonDeserialize(using = ThresholdValue.class) Integer consecutiveFailures,
        @NotNull @Min(AlertRule.MIN_THRESHOLD) @Max(AlertRule.MAX_THRESHOLD)
        @JsonDeserialize(using = ThresholdValue.class) Integer consecutiveRecoveries,
        @NotNull @JsonDeserialize(using = SeverityValue.class) AlertSeverity severity
) {
    // Restrict coercion on this API only; do not change Jackson behavior for existing endpoints.
    public static class ThresholdValue extends ValueDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "Thresholds must be JSON integers.");
            }
            return parser.getIntValue();
        }
    }

    public static class BooleanValue extends ValueDeserializer<Boolean> {
        @Override public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_TRUE)) return true;
            if (parser.hasToken(JsonToken.VALUE_FALSE)) return false;
            return context.reportInputMismatch(Boolean.class, "Enabled must be a JSON boolean.");
        }
    }

    public static class SeverityValue extends ValueDeserializer<AlertSeverity> {
        @Override public AlertSeverity deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                try { return AlertSeverity.valueOf(parser.getString()); }
                catch (IllegalArgumentException ignored) { /* Report the same safe error for every invalid value. */ }
            }
            return context.reportInputMismatch(AlertSeverity.class, "Severity must be LOW, MEDIUM, HIGH or CRITICAL.");
        }
    }
}
