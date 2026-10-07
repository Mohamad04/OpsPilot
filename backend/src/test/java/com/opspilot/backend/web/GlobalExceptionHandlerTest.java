package com.opspilot.backend.web;

import com.opspilot.backend.application.MonitoredServicesService;
import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.config.ApiSecurityExceptionHandler;
import com.opspilot.backend.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MonitoredServiceController.class)
@Import(SecurityConfig.class)
class GlobalExceptionHandlerTest {
    @Autowired MockMvc mockMvc;
    @Autowired ApiSecurityExceptionHandler securityErrorHandler;
    @MockitoBean MonitoredServicesService monitoredServicesService;

    static Stream<Arguments> expectedApplicationErrors() {
        return Stream.of(
                Arguments.of(new UserNotFoundException("Internal identity details"), 404, "User not found"),
                Arguments.of(new OrganisationMembershipNotFoundException("Internal tenant details"),
                        404, "Organisation membership not found"),
                Arguments.of(new OrganisationMembershipAlreadyExistsException("User is already a member"),
                        409, "User is already a member"),
                Arguments.of(new ForbiddenException("Operation forbidden"), 403, "Operation forbidden"),
                Arguments.of(new InsufficientOrganisationPermissionException("Insufficient permissions"),
                        403, "Insufficient permissions")
        );
    }

    @ParameterizedTest
    @MethodSource("expectedApplicationErrors")
    void applicationExceptionsUseConsistentJson(RuntimeException exception, int status, String message)
            throws Exception {
        UUID organisationId = UUID.randomUUID();
        when(monitoredServicesService.getAllByOrganisationId("user", organisationId)).thenThrow(exception);
        mockMvc.perform(get("/api/organisations/" + organisationId + "/monitored-services").with(jwt()))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void malformedOrganisationIdReturnsSafeBadRequest() throws Exception {
        mockMvc.perform(get("/api/organisations/not-a-uuid/monitored-services").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"status":400,"message":"Invalid request parameter","errors":{}}
                        """));
        verifyNoInteractions(monitoredServicesService);
    }

    @Test
    void securityAccessDenialUsesGenericJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        securityErrorHandler.handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("Sensitive security details"));
        assertEquals(403, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
        assertEquals("{\"status\":403,\"message\":\"Access denied\",\"errors\":{}}",
                response.getContentAsString());
    }
}
