package com.opspilot.backend.web;

import com.opspilot.backend.application.AuthenticatedUserService;
import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.application.OrganisationService;
import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.application.exception.OrganisationSlugAlreadyExistsException;
import com.opspilot.backend.config.SecurityConfig;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.domain.OrganisationMembership;
import com.opspilot.backend.domain.User;
import com.opspilot.backend.domain.exception.InvalidOrganisationNameException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.introspect.TypeResolutionContext;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@WebMvcTest(OrganisationController.class)
@Import(SecurityConfig.class)
public class OrganisationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrganisationService organisationService;

    @MockitoBean
    AuthenticatedUserService authenticatedUserService;

    @MockitoBean
    OrganisationMembershipService organisationMembershipService;

    @Test
    void createOrganisation_returnsCreated_whenRequestIsValid()
            throws Exception {

        String subject = "keycloak-user-123";

        User currentUser = User.create(
                subject,
                "Mohamad",
                "mohamad@example.com"
        );

        Organisation organisation =
                Organisation.create(
                        "Acme",
                        "slug-123"
                );

        OrganisationMembership ownerMembership =
                mock(OrganisationMembership.class);

        when(
                ownerMembership.getOrganisation()
        ).thenReturn(organisation);

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .createOrganisationWithOwner(
                                "Acme",
                                "slug-123",
                                currentUser
                        )
        ).thenReturn(ownerMembership);

        mockMvc.perform(
                        post("/api/organisations")
                                .with(
                                        jwt().jwt(jwt ->
                                                jwt.subject(subject)
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "name": "Acme",
                                      "slug": "slug-123"
                                    }
                                    """)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.slug").value("slug-123"));

        verify(
                authenticatedUserService
        ).findByIdentityProviderSubject(subject);

        verify(
                organisationMembershipService
        ).createOrganisationWithOwner(
                "Acme",
                "slug-123",
                currentUser
        );

        verifyNoInteractions(organisationService);
    }

    @Test
    void createOrganisation_returnsBadRequest_whenRequestIsInvalid()
            throws Exception {

        mockMvc.perform(
                        post("/api/organisations")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "",
                                          "slug": "Bad Slug"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value("Validation failed")
                )
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createOrganisation_returnsBadRequest_whenJsonIsMalformed()
            throws Exception {

        mockMvc.perform(
                        post("/api/organisations")
                                .with(jwt())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "name": "Acme",
                                          "slug":
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("Malformed JSON request")
                )
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void createOrganisation_returnsConflict_whenSlugAlreadyExists()
            throws Exception {

        String subject = "keycloak-user-123";

        User currentUser = User.create(
                subject,
                "Mohamad",
                "mohamad@example.com"
        );

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .createOrganisationWithOwner(
                                "acme",
                                "acme",
                                currentUser
                        )
        ).thenThrow(
                new OrganisationSlugAlreadyExistsException("acme")
        );

        mockMvc.perform(
                        post("/api/organisations")
                                .with(
                                        jwt().jwt(jwt ->
                                                jwt.subject(subject)
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "name": "acme",
                                      "slug": "acme"
                                    }
                                    """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "An organisation with slug 'acme' already exists"
                                )
                )
                .andExpect(jsonPath("$.errors").isEmpty());

        verify(
                organisationMembershipService
        ).createOrganisationWithOwner(
                "acme",
                "acme",
                currentUser
        );

        verifyNoInteractions(organisationService);
    }

    @Test
    void getAllOrganisations_returnsOnlyAuthenticatedUserOrganisations()
            throws Exception {

        String subject = "keycloak-user-123";

        UUID currentUserId =
                UUID.fromString("00000000-0000-0000-0000-000000000010");

        User currentUser = mock(User.class);
        when(currentUser.getId()).thenReturn(currentUserId);

        Organisation acme =
                Organisation.create("Acme", "acme");

        Organisation beta =
                Organisation.create("Beta", "beta");

        OrganisationMembership acmeMembership =
                mock(OrganisationMembership.class);

        OrganisationMembership betaMembership =
                mock(OrganisationMembership.class);

        when(acmeMembership.getOrganisation())
                .thenReturn(acme);

        when(betaMembership.getOrganisation())
                .thenReturn(beta);

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .getUserMemberships(currentUserId)
        ).thenReturn(
                List.of(
                        acmeMembership,
                        betaMembership
                )
        );

        mockMvc.perform(
                        get("/api/organisations")
                                .with(
                                        jwt().jwt(jwt ->
                                                jwt.subject(subject)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Acme"))
                .andExpect(jsonPath("$[0].slug").value("acme"))
                .andExpect(jsonPath("$[1].name").value("Beta"))
                .andExpect(jsonPath("$[1].slug").value("beta"));

        verify(
                authenticatedUserService
        ).findByIdentityProviderSubject(subject);

        verify(
                organisationMembershipService
        ).getUserMemberships(currentUserId);

        verifyNoInteractions(organisationService);
    }

    @Test
    void getAllOrganisations_returnsEmptyList_whenUserHasNoMemberships()
            throws Exception {

        String subject = "keycloak-user-123";

        UUID currentUserId =
                UUID.fromString("00000000-0000-0000-0000-000000000010");

        User currentUser = mock(User.class);

        when(currentUser.getId())
                .thenReturn(currentUserId);

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .getUserMemberships(currentUserId)
        ).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/organisations")
                                .with(
                                        jwt().jwt(jwt ->
                                                jwt.subject(subject)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        verify(
                authenticatedUserService
        ).findByIdentityProviderSubject(subject);

        verify(
                organisationMembershipService
        ).getUserMemberships(currentUserId);

        verifyNoInteractions(organisationService);
    }

    @Test
    void createOrganisation_returnsBadRequest_whenDomainNameIsInvalid()
            throws Exception {

        String subject = "keycloak-user-123";

        User currentUser = User.create(
                subject,
                "Mohamad",
                "mohamad@example.com"
        );

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .createOrganisationWithOwner(
                                "Acme",
                                "acme",
                                currentUser
                        )
        ).thenThrow(
                new InvalidOrganisationNameException(
                        "name cannot be null or blank"
                )
        );

        mockMvc.perform(
                        post("/api/organisations")
                                .with(
                                        jwt().jwt(jwt ->
                                                jwt.subject(subject)
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {
                                      "name": "Acme",
                                      "slug": "acme"
                                    }
                                    """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(
                        jsonPath("$.message")
                                .value("name cannot be null or blank")
                )
                .andExpect(jsonPath("$.errors").isEmpty());

        verify(
                organisationMembershipService
        ).createOrganisationWithOwner(
                "Acme",
                "acme",
                currentUser
        );

        verifyNoInteractions(organisationService);
    }

    @Test
    void getAllOrganisations_withoutAuthentication_returnsUnauthorized()
            throws Exception {

        mockMvc.perform(
                        get("/api/organisations")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(organisationService);
    }

    @Test
    void getOrganisation_returnsOrganisation_whenUserHasMembership()
            throws Exception {
        String subject = "keycloak-user-123";

        UUID currentUserId =
                UUID.fromString("00000000-0000-0000-0000-000000000010");

        UUID organisationId = UUID.randomUUID();

        User currentUser = mock(User.class);

        Organisation organisation =
                Organisation.create("Acme", "acme");

        OrganisationMembership membership =
                mock(OrganisationMembership.class);

        when(membership.getOrganisation())
                .thenReturn(organisation);

        when(currentUser.getId())
                .thenReturn(currentUserId);

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .requireMembership(
                            organisationId
                                , currentUserId
                        )
        ).thenReturn(membership);

        mockMvc.perform(
                get("/api/organisations/" + organisationId)
                        .with(
                                jwt().jwt(jwt ->
                                        jwt.subject(subject)
                                )
                        )
        ).andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.name")
                                .value("Acme")
                )
                .andExpect(
                        jsonPath("$.slug")
                                .value("acme")
                );

        verify(
                organisationMembershipService
        ).requireMembership(
                organisationId,
                currentUserId
        );
    }

    @Test
    void getOrganisation_returnsNotFound_whenUserHasNoMembership()
            throws Exception {
        String subject = "keycloak-user-123";

        UUID currentUserId =
                UUID.fromString("00000000-0000-0000-0000-000000000010");

        UUID organisationId = UUID.randomUUID();

        User currentUser = mock(User.class);

        when(currentUser.getId())
                .thenReturn(currentUserId);

        when(
                authenticatedUserService
                        .findByIdentityProviderSubject(subject)
        ).thenReturn(currentUser);

        when(
                organisationMembershipService
                        .requireMembership(
                                organisationId
                                , currentUserId
                        )
        ).thenThrow(OrganisationMembershipNotFoundException.class);

        mockMvc.perform(
                get("/api/organisations/" + organisationId)
                        .with(jwt().jwt(jwt ->
                                jwt.subject(subject))
        )
        ).andExpect(status().isNotFound());
    }
}