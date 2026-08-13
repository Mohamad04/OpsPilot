package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.OrganisationNotFoundException;
import com.opspilot.backend.application.exception.OrganisationSlugAlreadyExistsException;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.domain.OrganisationRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class OrganisationServiceTest {
    @Mock
    private OrganisationRepository organisationRepository;

    @InjectMocks
    private OrganisationService organisationService;

    @Test
    void createOrganisation_savesOrganisation_whenSlugDoesNotExist() {
        when(organisationRepository.existsBySlug("slug-123")).thenReturn(false);
        when(organisationRepository.save(any(Organisation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Organisation result = organisationService.createOrganisation("slug", "slug-123");
        assertEquals("slug-123", result.getSlug());
        assertEquals("slug", result.getName());

        verify(organisationRepository).save(any(Organisation.class));
    }
    @Test
    void createOrganisation_translatesSlugConstraintViolation() {
        when(organisationRepository.existsBySlug("slug-123"))
                .thenReturn(false);

        ConstraintViolationException constraintException =
                mock(ConstraintViolationException.class);

        when(constraintException.getConstraintName())
                .thenReturn("organisations_slug_key");

        DataIntegrityViolationException exception =
                new DataIntegrityViolationException(
                        "Unique constraint violation",
                        constraintException
                );

        when(organisationRepository.save(any(Organisation.class)))
                .thenThrow(exception);

        assertThrows(OrganisationSlugAlreadyExistsException.class, () ->
                organisationService.createOrganisation("slug", "slug-123"));
    }

    @Test
    void createOrganisation_throwsException_whenSlugAlreadyExists() {
        when(organisationRepository.existsBySlug("acme"))
                .thenReturn(true);

        assertThrows(
                OrganisationSlugAlreadyExistsException.class,
                () -> organisationService.createOrganisation("Acme", "acme")
        );
    }

    @Test
    void createOrganisation_rethrowsUnrelatedDataIntegrityViolation() {
        when(organisationRepository.existsBySlug("slug-123")).thenReturn(false);
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "Some database integrity error"
        );
        when(organisationRepository.save(any(Organisation.class))).thenThrow(exception);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> organisationService.createOrganisation(
                        "Acme",
                        "slug-123"
                )
        );

        assertSame(exception, thrown);
    }

    @Test
    void findById_whenIdExists_returnsOrganisation() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Organisation organisation = Organisation.create("Test","test-organisation");

        when(organisationRepository.findById(id))
                .thenReturn(Optional.of(organisation));
        Organisation result = organisationService.findById(id);
        assertSame(organisation, result);
    }

    @Test
    void findById_whenIdDoesNotExist_throwsOrganisationNotFoundException() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        when(organisationRepository.findById(id))
                .thenReturn(Optional.empty());

        assertThrows(
                OrganisationNotFoundException.class,
                () -> organisationService.findById(id)
        );
    }

    @Test
    void getAllOrganisations_whenOrganisationsExist_returnsAllOrganisations() {
        when(organisationRepository.findAll())
                .thenReturn(List.of(
                        Organisation.create("Test", "test-organisation"),
                        Organisation.create("Test2", "test-organisation2")
                ));

        List<Organisation> result = organisationService.getAllOrganisations();

        assertEquals(2, result.size());
        assertEquals("Test", result.get(0).getName());
        assertEquals("Test2", result.get(1).getName());
        assertEquals("test-organisation", result.get(0).getSlug());
        assertEquals("test-organisation2", result.get(1).getSlug());

        verify(organisationRepository).findAll();
    }

    @Test
    void getAllOrganisations_whenNoOrganisationsExist_returnsEmptyList(){
        when(organisationRepository.findAll())
                .thenReturn(List.of());
        List<Organisation> result = organisationService.getAllOrganisations();
        assertTrue(result.isEmpty());
        verify(organisationRepository).findAll();
    }
    }

