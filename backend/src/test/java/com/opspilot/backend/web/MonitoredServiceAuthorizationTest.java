package com.opspilot.backend.web;

import com.opspilot.backend.application.AuthenticatedUserService;
import com.opspilot.backend.application.MonitoredServicesService;
import com.opspilot.backend.application.OrganisationMembershipService;
import com.opspilot.backend.application.OrganisationService;
import com.opspilot.backend.config.SecurityConfig;
import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Keep the application services real so HTTP tests exercise the complete authorization flow.
@WebMvcTest(MonitoredServiceController.class)
@Import({SecurityConfig.class, MonitoredServicesService.class,
        AuthenticatedUserService.class, OrganisationMembershipService.class})
class MonitoredServiceAuthorizationTest {
    private static final String SUBJECT = "authenticated-subject";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORGANISATION_ID = UUID.randomUUID();
    private static final UUID OTHER_ORGANISATION_ID = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final String CREATE_REQUEST = """
            {"name":"Payment API","serviceType":"HTTP","environment":"PRODUCTION",
             "baseUrl":"https://payment.example.com","healthEndpoint":"/health","owner":"Payments"}
            """;

    @Autowired MockMvc mockMvc;
    @MockitoBean UserRepository userRepository;
    @MockitoBean OrganisationMembershipRepository membershipRepository;
    @MockitoBean OrganisationRepository organisationRepository;
    @MockitoBean MonitoredServiceRepository serviceRepository;
    @MockitoBean OrganisationService organisationService;
    @MockitoBean JwtDecoder jwtDecoder;

    private User user;
    private Organisation organisation;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(userRepository.findByIdentityProviderSubject(SUBJECT)).thenReturn(Optional.of(user));
        organisation = mock(Organisation.class);
        when(organisation.getId()).thenReturn(ORGANISATION_ID);
    }

    private void member(OrganisationRole role) {
        when(membershipRepository.findByOrganisationIdAndUserId(ORGANISATION_ID, USER_ID))
                .thenReturn(Optional.of(OrganisationMembership.create(organisation, user, role)));
    }

    private MonitoredService service(boolean enabled) {
        MonitoredService service = MonitoredService.create(organisation, "Payment API", ServiceType.HTTP,
                Environment.PRODUCTION, "https://payment.example.com", "/health", "Payments");
        if (!enabled) service.disable();
        return service;
    }

    private MockHttpServletRequestBuilder request(String operation, UUID organisationId) {
        String base = "/api/organisations/" + organisationId + "/monitored-services";
        return switch (operation) {
            case "list" -> get(base);
            case "read" -> get(base + "/" + SERVICE_ID);
            case "create" -> post(base).contentType(MediaType.APPLICATION_JSON).content(CREATE_REQUEST);
            case "enable", "disable" -> patch(base + "/" + SERVICE_ID + "/" + operation);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "read", "create", "enable", "disable"})
    void nonMemberCannotOperateOnAnotherOrganisation(String operation) throws Exception {
        member(OrganisationRole.OWNER);
        mockMvc.perform(request(operation, OTHER_ORGANISATION_ID)
                        .with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Organisation membership not found"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verify(userRepository).findByIdentityProviderSubject(SUBJECT);
        verify(membershipRepository).findByOrganisationIdAndUserId(OTHER_ORGANISATION_ID, USER_ID);
        verifyNoInteractions(serviceRepository, organisationRepository);
    }

    @ParameterizedTest
    @EnumSource(OrganisationRole.class)
    void allMembersCanListAndRead(OrganisationRole role) throws Exception {
        member(role);
        when(serviceRepository.findAllByOrganisationId(ORGANISATION_ID)).thenReturn(List.of(service(true)));
        when(serviceRepository.findByIdAndOrganisationId(SERVICE_ID, ORGANISATION_ID))
                .thenReturn(Optional.of(service(true)));
        mockMvc.perform(request("list", ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Payment API"));
        mockMvc.perform(request("read", ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.organisationId").value(ORGANISATION_ID.toString()));
    }

    @ParameterizedTest
    @CsvSource({"OWNER,create", "ADMIN,create", "ENGINEER,create",
            "OWNER,enable", "ADMIN,enable", "ENGINEER,enable",
            "OWNER,disable", "ADMIN,disable", "ENGINEER,disable"})
    void writersCanMutateTheirOrganisation(OrganisationRole role, String operation) throws Exception {
        member(role);
        if (operation.equals("create")) {
            when(organisationRepository.findById(ORGANISATION_ID)).thenReturn(Optional.of(organisation));
        } else {
            when(serviceRepository.findByIdAndOrganisationId(SERVICE_ID, ORGANISATION_ID))
                    .thenReturn(Optional.of(service(operation.equals("disable"))));
        }
        when(serviceRepository.save(any(MonitoredService.class))).thenAnswer(call -> call.getArgument(0));
        mockMvc.perform(request(operation, ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().is(operation.equals("create") ? 201 : 200))
                .andExpect(jsonPath("$.enabled").value(!operation.equals("disable")));
        verify(serviceRepository).save(any(MonitoredService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "enable", "disable"})
    void viewerCannotMutate(String operation) throws Exception {
        member(OrganisationRole.VIEWER);
        mockMvc.perform(request(operation, ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("VIEWER cannot modify monitored services"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verifyNoInteractions(serviceRepository, organisationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "enable", "disable"})
    void anotherOrganisationsServiceIdCannotBeUsedUnderOwnOrganisation(String operation) throws Exception {
        member(OrganisationRole.ADMIN);
        when(serviceRepository.findByIdAndOrganisationId(SERVICE_ID, ORGANISATION_ID))
                .thenReturn(Optional.empty());
        mockMvc.perform(request(operation, ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        verify(serviceRepository).findByIdAndOrganisationId(SERVICE_ID, ORGANISATION_ID);
        verify(serviceRepository, never()).findById(any());
        verify(serviceRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "read", "create", "enable", "disable"})
    void everyOperationRequiresAuthentication(String operation) throws Exception {
        mockMvc.perform(request(operation, ORGANISATION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.errors").isEmpty());
        verifyNoInteractions(userRepository, membershipRepository, serviceRepository, organisationRepository);
    }

    @Test
    void invalidBearerTokenReturnsGenericJsonWithoutDecoderDetails() throws Exception {
        when(jwtDecoder.decode("invalid-token"))
                .thenThrow(new BadJwtException("Sensitive JWT signature/issuer details"));
        mockMvc.perform(request("list", ORGANISATION_ID).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().json("""
                        {"status":401,"message":"Authentication required","errors":{}}
                        """));
        verifyNoInteractions(userRepository, membershipRepository, serviceRepository, organisationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "read", "create", "enable", "disable"})
    void authenticatedIdentityWithoutLocalUserReturnsNotFound(String operation) throws Exception {
        when(userRepository.findByIdentityProviderSubject(SUBJECT)).thenReturn(Optional.empty());
        mockMvc.perform(request(operation, ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"status":404,"message":"User not found","errors":{}}
                        """));
        verifyNoInteractions(membershipRepository, serviceRepository, organisationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enable", "disable"})
    void repeatingStatusChangeReturnsConflictWithoutSaving(String operation) throws Exception {
        member(OrganisationRole.ENGINEER);
        when(serviceRepository.findByIdAndOrganisationId(SERVICE_ID, ORGANISATION_ID))
                .thenReturn(Optional.of(service(operation.equals("enable"))));
        mockMvc.perform(request(operation, ORGANISATION_ID).with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Monitored Service is already "
                        + (operation.equals("enable") ? "enabled" : "disabled")));
        verify(serviceRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "{\"serviceType\":\"UNKNOWN\"}"})
    void invalidCreateRequestReturnsBadRequestBeforeBusinessLogic(String body) throws Exception {
        mockMvc.perform(request("create", ORGANISATION_ID).content(body)
                        .with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(userRepository, membershipRepository, serviceRepository, organisationRepository);
    }

    @Test
    void domainInvalidHealthEndpointReturnsBadRequestWithoutSaving() throws Exception {
        member(OrganisationRole.ADMIN);
        when(organisationRepository.findById(ORGANISATION_ID)).thenReturn(Optional.of(organisation));
        mockMvc.perform(request("create", ORGANISATION_ID).content(CREATE_REQUEST.replace("/health", "//health"))
                        .with(jwt().jwt(token -> token.subject(SUBJECT))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        "Health endpoint must be an absolute path starting with a single '/'."));
        verifyNoInteractions(serviceRepository);
    }
}
