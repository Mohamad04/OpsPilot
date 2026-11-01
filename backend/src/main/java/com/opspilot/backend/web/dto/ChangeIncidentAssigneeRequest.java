package com.opspilot.backend.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.UUID;

public record ChangeIncidentAssigneeRequest(
        @JsonProperty(value = "assigneeUserId", required = true)
        @JsonDeserialize(using = UuidValue.class) UUID assigneeUserId,
        @NotNull @PositiveOrZero
        @JsonDeserialize(using = ChangeIncidentStatusRequest.VersionValue.class) Long expectedVersion) {
    public static class UuidValue extends ValueDeserializer<UUID> {
        @Override public UUID deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                String value = parser.getString();
                try {
                    UUID id = UUID.fromString(value);
                    if (id.toString().equalsIgnoreCase(value)) return id;
                } catch (IllegalArgumentException ignored) { /* Reject noncanonical UUIDs too. */ }
            }
            return context.reportInputMismatch(UUID.class, "assigneeUserId must be a UUID string or null");
        }
    }
}
