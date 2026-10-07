package com.opspilot.backend.web;

import com.opspilot.backend.application.AuthenticatedUserService;
import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.domain.OrganisationMembership;
import com.opspilot.backend.domain.User;
import com.opspilot.backend.web.dto.CreateOrganisationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
public class OrganisationController {

    private final AuthenticatedUserService authenticatedUserService;
    private final OrganisationMembershipService organisationMembershipService;

    public OrganisationController(AuthenticatedUserService authenticatedUserService,
                                  OrganisationMembershipService organisationMembershipService) {
        this.authenticatedUserService = authenticatedUserService;
        this.organisationMembershipService = organisationMembershipService;
    }

    @GetMapping("/api/organisations")
    public List<Organisation> getAllOrganisations(
            @AuthenticationPrincipal Jwt jwt
    ) {
        String subject = jwt.getSubject();

        User currentUser =
                authenticatedUserService
                        .findByIdentityProviderSubject(subject);

        List<OrganisationMembership> memberships =
                organisationMembershipService
                        .getUserMemberships(currentUser.getId());

        return memberships
                .stream()
                .map(OrganisationMembership::getOrganisation)
                .toList();
    }

    @GetMapping("/api/organisations/{id}")
    public Organisation getOrganisation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        String subject = jwt.getSubject();

        User currentUser =
                authenticatedUserService
                        .findByIdentityProviderSubject(subject);
        OrganisationMembership membership =
                organisationMembershipService
                        .requireMembership(id,  currentUser.getId());

        return membership.getOrganisation();
    }

    @PostMapping("/api/organisations")
    @ResponseStatus(HttpStatus.CREATED)
    public Organisation createOrganisation(
            @Valid @RequestBody CreateOrganisationRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String subject = jwt.getSubject();

        User currentUser =
                authenticatedUserService
                        .findByIdentityProviderSubject(subject);

        OrganisationMembership ownerMembership =
                organisationMembershipService
                        .createOrganisationWithOwner(
                                request.name(),
                                request.slug(),
                                currentUser
                        );

        return ownerMembership.getOrganisation();
    }

}