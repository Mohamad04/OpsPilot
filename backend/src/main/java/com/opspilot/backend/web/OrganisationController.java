package com.opspilot.backend.web;

import com.opspilot.backend.application.OrganisationService;
import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.web.dto.CreateOrganisationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class OrganisationController {

    private final OrganisationService organisationService;

    public OrganisationController(OrganisationService organisationService) {
        this.organisationService = organisationService;
    }

    @GetMapping("/api/organisations")
    public List<Organisation> getAllOrganisations() {
        return organisationService.getAllOrganisations();
    }

    @PostMapping("/api/organisations")
    @ResponseStatus(HttpStatus.CREATED)
    public Organisation createOrganisation(@Valid @RequestBody CreateOrganisationRequest request) {
        return organisationService.createOrganisation(request.name(),  request.slug());
    }

    @GetMapping("/api/organisations/{id}")
    public Organisation getOrganisation(@PathVariable UUID id) {
    return organisationService.findById(id);
    }

}