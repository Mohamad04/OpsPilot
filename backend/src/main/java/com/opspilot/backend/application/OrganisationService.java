package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.application.exception.OrganisationSlugAlreadyExistsException;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.domain.OrganisationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class OrganisationService {
    private final OrganisationRepository organisationRepository;
    private static final String ORGANISATION_SLUG_UNIQUE_CONSTRAINT =
            "organisations_slug_key";

    public OrganisationService(OrganisationRepository organisationRepository) {
        this.organisationRepository = organisationRepository;
    }
    public List<Organisation> getAllOrganisations() {
        return organisationRepository.findAll();
    }
    public Organisation createOrganisation(String name, String slug) {
        if (organisationRepository.existsBySlug(slug)) {
            throw new OrganisationSlugAlreadyExistsException(slug);
        }
        try{
            Organisation organisation = Organisation.create(name, slug);
            return organisationRepository.save(organisation);
        } catch (DataIntegrityViolationException exception){
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintException) {
                    if(ORGANISATION_SLUG_UNIQUE_CONSTRAINT.equals(
                            constraintException.getConstraintName())) {
                        throw new OrganisationSlugAlreadyExistsException(slug);
                    }
                }
                cause = cause.getCause();
            }
            throw exception;
        }

    }

    public Organisation findById(UUID id) {
        return organisationRepository.findById(id)
                .orElseThrow(() -> new OrganisationNotFoundException(id));
    }
}
