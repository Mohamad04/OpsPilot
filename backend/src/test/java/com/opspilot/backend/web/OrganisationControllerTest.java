package com.opspilot.backend.web;

import com.opspilot.backend.application.exception.OrganisationSlugAlreadyExistsException;
import com.opspilot.backend.domain.Organisation;
import com.opspilot.backend.application.OrganisationService;
import com.opspilot.backend.domain.exception.InvalidOrganisationNameException;
import com.opspilot.backend.domain.exception.InvalidOrganisationSlugException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@WebMvcTest(OrganisationController.class)
public class OrganisationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrganisationService organisationService;

    @Test
    void createOrganisation_returnsCreated_whenRequestIsValid() throws Exception {
        Organisation organisation =
                Organisation.create("Acme", "slug-123");

        when(organisationService.createOrganisation("Acme", "slug-123"))
                .thenReturn(organisation);

        mockMvc.perform(
                        post("/api/organisations")
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
    }
    @Test
    void createOrganisation_returnsBadRequest_whenRequestIsInvalid() throws Exception {
        mockMvc.perform(
                        post("/api/organisations")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                            {
                              "name": "",
                              "slug": "Bad Slug"
                            }
                            """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400));
    }
    @Test
    void createOrganisation_returnsBadRequest_whenJsonIsMalformed() throws Exception {
        mockMvc.perform(
                post("/api/organisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {
                  "name": "Acme",
                  "slug":
                }
                """)
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Malformed JSON request"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void createOrganisation_returnsConflict_whenSlugAlreadyExists() throws Exception {
        when(organisationService.createOrganisation("acme", "acme"))
                .thenThrow(new  OrganisationSlugAlreadyExistsException("acme"));
        mockMvc.perform(
                post("/api/organisations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                "name": "acme",
                                "slug":"acme"
                                }
                                """)
        ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message")
                        .value("An organisation with slug 'acme' already exists"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void getAllOrganisations_returnsOrganisations_whenOrganisationsExist() throws Exception {
        Organisation acme = Organisation.create("Acme", "acme");
        Organisation beta = Organisation.create("Beta", "beta");

        when(organisationService.getAllOrganisations())
                .thenReturn(List.of(acme, beta));

        mockMvc.perform(
                        get("/api/organisations")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Acme"))
                .andExpect(jsonPath("$[0].slug").value("acme"))
                .andExpect(jsonPath("$[1].name").value("Beta"))
                .andExpect(jsonPath("$[1].slug").value("beta"));
    }

    @Test
    void getAllOrganisations_returnsEmptyList_whenNoOrganisationsExist() throws Exception {
        when(organisationService.getAllOrganisations()).thenReturn(List.of());
        mockMvc.perform(
                get("/api/organisations")
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void createOrganisation_returnsBadRequest_whenDomainNameIsInvalid()
            throws Exception {

        when(organisationService.createOrganisation("Acme", "acme"))
                .thenThrow(new InvalidOrganisationNameException(
                        "name cannot be null or blank"
                ));

        mockMvc.perform(
                        post("/api/organisations")
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
                .andExpect(jsonPath("$.message")
                        .value("name cannot be null or blank"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void createOrganisation_returnsBadRequest_whenDomainSlugIsInvalid() throws Exception {
        when(organisationService.createOrganisation("Acme", "acme")).thenThrow(
                new InvalidOrganisationSlugException("slug cannot be null or blank")
        );
        mockMvc.perform(
                post("/api/organisations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Acme",
                      "slug": "acme"
                    }
                """)
        ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.message")
                        .value("slug cannot be null or blank"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
