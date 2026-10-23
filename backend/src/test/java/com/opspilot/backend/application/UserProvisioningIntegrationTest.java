package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.ForbiddenException;
import com.opspilot.backend.domain.*;
import com.opspilot.backend.web.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserProvisioningIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");

    @Autowired MockMvc mockMvc;
    @Autowired UserProvisioningService provisioning;
    @Autowired AuthenticatedUserService authenticatedUsers;
    @Autowired UserRepository users;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanDatabase() {
        memberships.deleteAll();
        organisations.deleteAll();
        users.deleteAll();
    }

    private MockHttpServletRequestBuilder request(String subject, String email, Object verified) {
        return post("/api/me/provision").with(jwt().jwt(token -> {
            token.subject(subject).claim("name", "Alex Example").claim("preferred_username", "alex");
            if (email != null) token.claim("email", email);
            if (verified != null) token.claim("email_verified", verified);
        }));
    }

    @Test
    void firstLoginCreatesActiveUserWithoutMembershipAndCanReadEmptyOrganisationList() throws Exception {
        mockMvc.perform(request("new-subject", "Alex@Example.com", true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject":"victim-subject","userId":"attacker-chosen","role":"OWNER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.displayName").value("Alex Example"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.identityProviderSubject").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist());
        User user = users.findByIdentityProviderSubject("new-subject").orElseThrow();
        assertEquals("alex@example.com", user.getEmail());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertEquals(1, users.count());
        assertEquals(0, memberships.count());
        assertTrue(users.findByIdentityProviderSubject("victim-subject").isEmpty());
        mockMvc.perform(get("/api/organisations").with(jwt().jwt(token -> token.subject("new-subject"))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void repeatedProvisioningRetainsIdentityProfileAndOriginalMemberships() throws Exception {
        User existing = users.save(User.create("existing", "Original name", "original@example.com"));
        Organisation organisation = organisations.save(Organisation.create("Acme", "acme"));
        OrganisationMembership membership = memberships.save(OrganisationMembership.create(organisation, existing, OrganisationRole.VIEWER));

        // Existing subject mapping is authoritative even when optional profile claims change/disappear.
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(request("existing", null, false))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(existing.getId().toString()))
                    .andExpect(jsonPath("$.displayName").value("Original name"));
        }
        assertEquals(1, users.count());
        assertEquals("original@example.com", users.findById(existing.getId()).orElseThrow().getEmail());
        assertEquals(1, memberships.count());
        assertEquals(OrganisationRole.VIEWER, memberships.findById(membership.getId()).orElseThrow().getRole());
        mockMvc.perform(get("/api/organisations").with(jwt().jwt(token -> token.subject("existing"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(organisation.getId().toString()));
    }

    @Test
    void newlyProvisionedUserCanExplicitlyCreateOrganisationWithOwner() throws Exception {
        mockMvc.perform(request("creator", "creator@example.com", true)).andExpect(status().isOk());
        assertEquals(0, memberships.count());
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject("creator")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"New organisation","slug":"new-organisation"}
                                """))
                .andExpect(status().isCreated());
        List<OrganisationMembership> created = memberships.findAllByUserId(users.findByIdentityProviderSubject("creator").orElseThrow().getId());
        assertEquals(1, created.size());
        assertEquals(OrganisationRole.OWNER, created.getFirst().getRole());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"", "Bad Slug", "-bad", "bad-", "bad--slug", "a"})
    void invalidOrganisationSlugCreatesNeitherOrganisationNorMembership(String slug) throws Exception {
        provisioning.provision("creator", "creator@example.com", true, "Creator", null);
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject("creator")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
                                java.util.Map.of("name", "Team", "slug", slug == null ? "" : slug))))
                .andExpect(status().isBadRequest());
        assertEquals(0, organisations.count());
        assertEquals(0, memberships.count());
    }

    @Test
    void organisationCreatorAndOwnerRoleComeFromJwtAndServerWorkflow() throws Exception {
        User other = users.save(User.create("other", "Other", "other@example.com"));
        UserResponse actor = provisioning.provision("creator", "creator@example.com", true, "Creator", null);
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject("creator")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"Owned team","slug":"owned-team","ownerUserId":"%s","role":"VIEWER"}
                                """.formatted(other.getId())))
                .andExpect(status().isCreated());
        List<OrganisationMembership> owned = memberships.findAllByUserId(actor.id());
        assertEquals(1, owned.size());
        assertEquals(OrganisationRole.OWNER, owned.getFirst().getRole());
        assertTrue(memberships.findAllByUserId(other.getId()).isEmpty());
    }

    @Test
    void duplicateSlugDoesNotCreateMembershipOrExposeAnotherTenantsOrganisation() throws Exception {
        UserResponse first = provisioning.provision("first", "first@example.com", true, "First", null);
        UserResponse second = provisioning.provision("second", "second@example.com", true, "Second", null);
        String body = """
                {"name":"Team","slug":"shared-slug"}
                """;
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject("first")))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/organisations").with(jwt().jwt(token -> token.subject("second")))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertEquals(1, organisations.count());
        assertEquals(1, memberships.count());
        assertTrue(memberships.findAllByUserId(second.id()).isEmpty());
        UUID organisationId = memberships.findAllByUserId(first.id()).getFirst().getOrganisation().getId();
        mockMvc.perform(get("/api/organisations/" + organisationId)
                        .with(jwt().jwt(token -> token.subject("second")))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organisations").with(jwt().jwt(token -> token.subject("second"))))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid-email", "name@example.com\nInjected", "  "})
    void missingOrInvalidEmailCannotCreateUser(String email) throws Exception {
        mockMvc.perform(request("new", email, true)).andExpect(status().isForbidden());
        assertEquals(0, users.count());
        assertEquals(0, memberships.count());
    }

    @Test
    void missingOrUnverifiedEmailCannotCreateUser() throws Exception {
        for (Object verified : new Object[] {null, false, "true"}) {
            mockMvc.perform(request("new", "new@example.com", verified))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("A verified, valid Keycloak email address is required to create your OpsPilot account."));
        }
        assertEquals(0, users.count());
    }

    @Test
    void emailConflictCannotLinkOrModifyAnExistingAccountEvenIgnoringCase() throws Exception {
        User victim = users.save(User.create("victim", "Original", "Owner@Example.com"));
        mockMvc.perform(request("other-subject", "owner@example.com", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertEquals(1, users.count());
        assertTrue(users.findByIdentityProviderSubject("other-subject").isEmpty());
        assertEquals("victim", users.findById(victim.getId()).orElseThrow().getIdentityProviderSubject());
        assertEquals("Original", users.findById(victim.getId()).orElseThrow().getDisplayName());
        assertEquals(0, memberships.count());
    }

    @Test
    void inactiveAccountIsNotReactivatedAndCannotBypassProvisioningThroughOrganisationEndpoints() throws Exception {
        User inactive = users.save(User.create("inactive", "Disabled", "disabled@example.com"));
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", inactive.getId());
        mockMvc.perform(request("inactive", "disabled@example.com", true)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organisations").with(jwt().jwt(token -> token.subject("inactive"))))
                .andExpect(status().isForbidden());
        assertEquals(UserStatus.INACTIVE, users.findById(inactive.getId()).orElseThrow().getStatus());
        assertThrows(ForbiddenException.class, () -> authenticatedUsers.findByIdentityProviderSubject("inactive"));
        assertEquals(0, memberships.count());
    }

    @Test
    void concurrentSameSubjectProvisioningReturnsOneLocalIdentity() throws Exception {
        int requests = 6;
        CyclicBarrier start = new CyclicBarrier(requests);
        try (var executor = Executors.newFixedThreadPool(requests)) {
            var futures = IntStream.range(0, requests).mapToObj(index -> executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return provisioning.provision("concurrent", "concurrent@example.com", true, "Concurrent", "concurrent");
            })).toList();
            UUID id = futures.getFirst().get(30, TimeUnit.SECONDS).id();
            for (var future : futures) assertEquals(id, future.get(30, TimeUnit.SECONDS).id());
        }
        assertEquals(1, users.count());
        assertEquals(0, memberships.count());
    }

    @Test
    void concurrentDifferentSubjectsCannotShareAnEmailOrTakeOverEachOther() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    provisioning.provision("subject-" + index, "Shared@Example.com", true, "Name", null);
                    return "created";
                } catch (com.opspilot.backend.application.exception.UserProvisioningConflictException expected) {
                    return "conflict";
                }
            })).toList();
            var results = List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
            assertTrue(results.contains("created"));
            assertTrue(results.contains("conflict"));
        }
        assertEquals(1, users.count());
        assertEquals(0, memberships.count());
    }

    @Test
    void profileFallbacksRespectExistingDatabaseLengthAndSubjectRequirements() {
        UserResponse fallback = provisioning.provision("fallback", "fallback@example.com", true, " ", "username");
        assertEquals("username", fallback.displayName());
        UserResponse generic = provisioning.provision("generic", "generic@example.com", true, null, null);
        assertEquals("OpsPilot user", generic.displayName());
        UserResponse longName = provisioning.provision("long", "long@example.com", true, "😀".repeat(110), null);
        assertEquals(100, longName.displayName().codePointCount(0, longName.displayName().length()));
        assertThrows(ForbiddenException.class, () -> provisioning.provision(" ", "email@example.com", true, "Name", null));
        assertThrows(ForbiddenException.class, () -> provisioning.provision(null, "email@example.com", true, "Name", null));
        assertThrows(ForbiddenException.class, () -> provisioning.provision("s".repeat(256), "email@example.com", true, "Name", null));
        assertThrows(ForbiddenException.class, () -> provisioning.provision("long-email", "a".repeat(250) + "@example.com", true, "Name", null));
    }
}
