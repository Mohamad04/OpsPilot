package com.opspilot.backend.web;

import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.web.dto.CreateOrganisationRequest;
import com.opspilot.backend.web.dto.OrganisationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class OrganisationController {
    private final OrganisationMembershipService organisationMembershipService;

    public OrganisationController(OrganisationMembershipService organisationMembershipService) {
        this.organisationMembershipService = organisationMembershipService;
    }

    @GetMapping("/api/organisations")
    public List<OrganisationResponse> getAllOrganisations(@AuthenticationPrincipal Jwt jwt) {
        return organisationMembershipService.getOrganisations(jwt.getSubject());
    }

    @GetMapping("/api/organisations/{id}")
    public OrganisationResponse getOrganisation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return organisationMembershipService.getOrganisation(jwt.getSubject(), id);
    }

    @PostMapping("/api/organisations")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganisationResponse createOrganisation(@Valid @RequestBody CreateOrganisationRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return organisationMembershipService.createOrganisationWithOwner(jwt.getSubject(), request.name(), request.slug());
    }
}
