package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ChangeIncidentStatusRequest(
        @NotNull @JsonDeserialize(using = StatusValue.class) IncidentStatus status,
        @NotNull @PositiveOrZero @JsonDeserialize(using = VersionValue.class) Long expectedVersion) {
    // Match alert-rule DTO conventions: enforce strict types for this API without global coercion changes.
    public static class StatusValue extends ValueDeserializer<IncidentStatus> {
        @Override public IncidentStatus deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                try { return IncidentStatus.valueOf(parser.getString()); }
                catch (IllegalArgumentException ignored) { /* Use a safe input error for unsupported values. */ }
            }
            return context.reportInputMismatch(IncidentStatus.class, "Status must be a supported incident status string");
        }
    }

    public static class VersionValue extends ValueDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "expectedVersion must be a JSON integer");
            }
            return parser.getLongValue();
        }
    }
}
