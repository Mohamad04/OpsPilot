package com.opspilot.backend.domain;


import com.opspilot.backend.domain.exception.InvalidOrganisationNameException;
import com.opspilot.backend.domain.exception.InvalidOrganisationSlugException;
import com.opspilot.backend.domain.exception.OrganisationAlreadyInStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OrganisationTest {

    @Test
    void createOrganisation_createsOrganisation_whenNameAndSlugAreValid() {
        Organisation organisation =
                Organisation.create("Acme", "acme");

        // assertions
        assertEquals("Acme", organisation.getName());
        assertEquals("acme", organisation.getSlug());
        assertNotNull(organisation.getCreatedAt());
        assertEquals(OrganisationStatus.INACTIVE, organisation.getStatus());
    }
    @Test
    void createOrganisation_throwsException_whenNameIsBlank(){
        assertThrows(InvalidOrganisationNameException.class,
                () -> Organisation.create("", "acme"));
    }
    @Test
    void createOrganisation_throwsException_whenSlugIsTooShort(){
        assertThrows(InvalidOrganisationSlugException.class,
                () -> Organisation.create("Acme", "a"));
    }
    @Test
    void createOrganisation_throwsException_whenSlugHasInvalidFormat(){
        assertThrows(InvalidOrganisationSlugException.class,
                () -> Organisation.create("Acme", "Bad Slug"));
    }
    @Test
    void activate_changesStatusToActive() {
        Organisation organisation = Organisation.create("Acme", "acme");
        organisation.activate();
        assertEquals(OrganisationStatus.ACTIVE, organisation.getStatus());
    }

    @Test
    void activate_throwsException_whenAlreadyActive() {
        Organisation organisation = Organisation.create("Acme", "acme");
        organisation.activate();
        assertThrows(OrganisationAlreadyInStatusException.class,
                organisation::activate);
    }

    @Test
    void deactivate_changesStatusToInactive() {
        Organisation organisation = Organisation.create("Acme", "acme");
        organisation.activate();
        organisation.deactivate();
        assertEquals(OrganisationStatus.INACTIVE, organisation.getStatus());
    }

    @Test
    void deactivate_throwsException_whenAlreadyInactive() {
        Organisation organisation = Organisation.create("Acme", "acme");
        assertThrows(OrganisationAlreadyInStatusException.class,
                organisation::deactivate);
    }

    @Test
    void createOrganisation_throwsException_whenNameIsTooLong(){
        String name = "a".repeat(101);
        assertThrows(InvalidOrganisationNameException.class,
                () -> Organisation.create(name, "acme"));
    }

    @Test
    void createOrganisation_throwsException_whenSlugIsBlank(){
        assertThrows(InvalidOrganisationSlugException.class,
                () -> Organisation.create("Acme", ""));
    }

    @Test
    void createOrganisation_throwsException_whenSlugIsTooLong(){
        String slug = "a".repeat(101);
        assertThrows(InvalidOrganisationSlugException.class,
                () ->  Organisation.create("Acme", slug));
    }

    @Test
    void rename_changesName_whenNameIsValid(){
        Organisation organisation = Organisation.create("Acme", "acme");
        organisation.rename("Acmee");
        assertEquals("Acmee", organisation.getName());
    }

    @Test
    void rename_throwsException_whenNameIsInvalid(){
        Organisation organisation = Organisation.create("Acme", "acme");
        String newName = "a".repeat(101);
        assertThrows(InvalidOrganisationNameException.class,
                () -> organisation.rename(newName));
    }

    @Test
    void changeSlug_changesSlug_whenSlugIsValid(){
        Organisation organisation = Organisation.create("Acme", "acme");
        organisation.changeSlug("acmee");
        assertEquals("acmee", organisation.getSlug());
    }

    @Test
    void changeSlug_throwsException_whenSlugIsInvalid(){
        Organisation organisation = Organisation.create("Acme", "acme");
        String newSlug = "a".repeat(101);
        assertThrows(InvalidOrganisationSlugException.class,
                () -> organisation.changeSlug(newSlug));
    }
}
