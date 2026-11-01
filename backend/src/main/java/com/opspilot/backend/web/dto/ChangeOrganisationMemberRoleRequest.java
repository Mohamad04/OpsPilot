package com.opspilot.backend.web.dto;

import com.opspilot.backend.domain.OrganisationRole;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ChangeOrganisationMemberRoleRequest(
        @NotNull @JsonDeserialize(using = AddOrganisationMemberRequest.RoleValue.class) OrganisationRole role) { }
