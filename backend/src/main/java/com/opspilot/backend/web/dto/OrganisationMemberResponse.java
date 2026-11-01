package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.OrganisationMembership;
import com.opspilot.backend.domain.OrganisationRole;
import java.util.UUID;

public record OrganisationMemberResponse(UUID id, UUID organisationId, UUID userId, String displayName,
                                         OrganisationRole role) {
    public static OrganisationMemberResponse from(OrganisationMembership membership) {
        return new OrganisationMemberResponse(membership.getId(), membership.getOrganisation().getId(),
                membership.getUser().getId(), membership.getUser().getDisplayName(), membership.getRole());
    }
}
