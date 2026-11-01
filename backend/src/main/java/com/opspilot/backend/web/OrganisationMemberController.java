package com.opspilot.backend.web;

import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.web.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/organisations/{organisationId}/members")
public class OrganisationMemberController {
    private final OrganisationMembershipService memberships;

    public OrganisationMemberController(OrganisationMembershipService memberships) { this.memberships = memberships; }

    @GetMapping
    public OrganisationMemberPageResponse list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return memberships.listMembers(jwt.getSubject(), organisationId, page, size);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public OrganisationMemberResponse add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @Valid @RequestBody AddOrganisationMemberRequest request) {
        return memberships.addMembership(jwt.getSubject(), organisationId, request.userId(), request.role());
    }

    @PatchMapping("/{membershipId}")
    public OrganisationMemberResponse changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId,
            @PathVariable UUID membershipId, @Valid @RequestBody ChangeOrganisationMemberRoleRequest request) {
        return memberships.updateMembership(jwt.getSubject(), organisationId, membershipId, request.role());
    }

    @DeleteMapping("/{membershipId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organisationId, @PathVariable UUID membershipId) {
        memberships.deleteMembership(jwt.getSubject(), organisationId, membershipId);
    }
}
