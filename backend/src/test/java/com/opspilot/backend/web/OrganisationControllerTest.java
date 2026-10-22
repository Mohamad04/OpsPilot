package com.opspilot.backend.web;

import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.application.exception.OrganisationMembershipNotFoundException;
import com.opspilot.backend.application.exception.OrganisationSlugAlreadyExistsException;
import com.opspilot.backend.config.SecurityConfig;
import com.opspilot.backend.domain.OrganisationStatus;
import com.opspilot.backend.domain.exception.InvalidOrganisationNameException;
import com.opspilot.backend.web.dto.OrganisationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrganisationController.class)
@Import(SecurityConfig.class)
class OrganisationControllerTest {
    private static final String SUBJECT = "keycloak-user-123";
    private static final UUID ID = UUID.randomUUID();
    private static final OrganisationResponse ORGANISATION = new OrganisationResponse(
            ID, "Acme", "acme", OrganisationStatus.INACTIVE, Instant.parse("2026-10-07T10:00:00Z"));

    @Autowired MockMvc mockMvc;
    @MockitoBean OrganisationMembershipService organisationMembershipService;

    @Test
    void createOrganisationReturnsExplicitResponseContract() throws Exception {
        when(organisationMembershipService.createOrganisationWithOwner(SUBJECT, "Acme", "acme"))
                .thenReturn(ORGANISATION);
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject(SUBJECT)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Acme","slug":"acme"}
                                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.slug").value("acme"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.length()").value(5));
        verify(organisationMembershipService).createOrganisationWithOwner(SUBJECT, "Acme", "acme");
    }

    @Test
    void getAllOrganisationsReturnsOnlyServiceDtos() throws Exception {
        when(organisationMembershipService.getOrganisations(SUBJECT)).thenReturn(List.of(ORGANISATION));
        mockMvc.perform(get("/api/organisations").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Acme"))
                .andExpect(jsonPath("$[0].length()").value(5));
        verify(organisationMembershipService).getOrganisations(SUBJECT);
    }

    @Test
    void getAllOrganisationsReturnsEmptyListWhenNoMemberships() throws Exception {
        when(organisationMembershipService.getOrganisations(SUBJECT)).thenReturn(List.of());
        mockMvc.perform(get("/api/organisations").with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void getOrganisationReturnsDto() throws Exception {
        when(organisationMembershipService.getOrganisation(SUBJECT, ID)).thenReturn(ORGANISATION);
        mockMvc.perform(get("/api/organisations/" + ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.length()").value(5));
        verify(organisationMembershipService).getOrganisation(SUBJECT, ID);
    }

    @Test
    void foreignOrganisationReturnsNotFound() throws Exception {
        when(organisationMembershipService.getOrganisation(SUBJECT, ID))
                .thenThrow(new OrganisationMembershipNotFoundException("Membership missing"));
        mockMvc.perform(get("/api/organisations/" + ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/organisations").with(jwt()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","slug":"Bad Slug"}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Validation failed"));
        verifyNoInteractions(organisationMembershipService);
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/organisations").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed JSON request"));
        verifyNoInteractions(organisationMembershipService);
    }

    @Test
    void duplicateSlugReturnsConflict() throws Exception {
        when(organisationMembershipService.createOrganisationWithOwner(SUBJECT, "Acme", "acme"))
                .thenThrow(new OrganisationSlugAlreadyExistsException("acme"));
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject(SUBJECT)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Acme","slug":"acme"}
                                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidDomainNameReturnsBadRequest() throws Exception {
        when(organisationMembershipService.createOrganisationWithOwner(SUBJECT, "Acme", "acme"))
                .thenThrow(new InvalidOrganisationNameException("Invalid name"));
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject(SUBJECT)))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Acme","slug":"acme"}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/organisations")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(organisationMembershipService);
    }
}
