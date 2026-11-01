package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.OrganisationRole;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record AddOrganisationMemberRequest(
        @NotNull @JsonDeserialize(using = UserIdValue.class) UUID userId,
        @NotNull @JsonDeserialize(using = RoleValue.class) OrganisationRole role) {
    public static class RoleValue extends ValueDeserializer<OrganisationRole> {
        @Override public OrganisationRole deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                try { return OrganisationRole.valueOf(parser.getString()); }
                catch (IllegalArgumentException ignored) { /* Reject unknown roles, including realm roles. */ }
            }
            return context.reportInputMismatch(OrganisationRole.class, "role must be OWNER, ADMIN, ENGINEER or VIEWER");
        }
    }
    public static class UserIdValue extends ValueDeserializer<UUID> {
        @Override public UUID deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) {
                String value = parser.getString();
                try {
                    UUID id = UUID.fromString(value);
                    if (id.toString().equalsIgnoreCase(value)) return id;
                } catch (IllegalArgumentException ignored) { /* Do not accept abbreviated UUIDs. */ }
            }
            return context.reportInputMismatch(UUID.class, "userId must be a canonical UUID string");
        }
    }
}
