package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.OrganisationStatus;

import java.time.Instant;
import java.util.UUID;

public record OrganisationResponse(UUID id, String name, String slug,
                                   OrganisationStatus status, Instant createdAt) {
}
