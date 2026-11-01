package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.IncidentEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record AddIncidentCommentRequest(
        @NotBlank @Size(max = IncidentEvent.DETAILS_MAX_LENGTH) @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ContentValue.class) String content) {
    public static class ContentValue extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) return parser.getString();
            return context.reportInputMismatch(String.class, "content must be a text string");
        }
    }
}
