package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.*;
import com.opspilot.backend.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "opspilot.monitoring.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrganisationMembershipManagementIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    @Autowired MockMvc mvc;
    @Autowired OrganisationMembershipService application;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired OrganisationRepository organisations;
    @Autowired UserRepository users;
    @Autowired IncidentRepository incidents;
    @Autowired IncidentEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Flyway flyway;
    @MockitoBean JwtDecoder decoder;
    private Organisation alpha, beta;
    private OrganisationMembership owner, admin, engineer, viewer;
    private User candidate;

    @BeforeEach void fixtures() {
        alpha = organisations.save(Organisation.create("Membership Alpha", "members-a-" + UUID.randomUUID()));
        beta = organisations.save(Organisation.create("Membership Beta", "members-b-" + UUID.randomUUID()));
        owner = member(alpha, OrganisationRole.OWNER);
        admin = member(alpha, OrganisationRole.ADMIN);
        engineer = member(alpha, OrganisationRole.ENGINEER);
        viewer = member(alpha, OrganisationRole.VIEWER);
        candidate = user();
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Invalid test token"));
    }
    private User user() {
        String subject = "member-" + UUID.randomUUID();
        return users.save(User.create(subject, "Demo member", subject + "@example.test"));
    }
    private OrganisationMembership member(Organisation org, OrganisationRole role) {
        return memberships.save(OrganisationMembership.create(org, user(), role));
    }
    private String subject(OrganisationMembership member) { return member.getUser().getIdentityProviderSubject(); }
    private String base(UUID org) { return "/api/organisations/" + org + "/members"; }
    private String base() { return base(alpha.getId()); }
    private ResultActions add(OrganisationMembership actor, UUID target, OrganisationRole role) throws Exception {
        return mvc.perform(post(base()).with(jwt().jwt(t -> t.subject(subject(actor))))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + target + "\",\"role\":\"" + role + "\"}"));
    }
    private ResultActions change(OrganisationMembership actor, UUID id, OrganisationRole role) throws Exception {
        return mvc.perform(patch(base() + "/" + id).with(jwt().jwt(t -> t.subject(subject(actor))))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"));
    }
    private ResultActions remove(OrganisationMembership actor, UUID id) throws Exception {
        return mvc.perform(delete(base() + "/" + id).with(jwt().jwt(t -> t.subject(subject(actor)))));
    }
    private long auditCount() { return jdbc.queryForObject("select count(*) from membership_audit_events where organisation_id=?", Long.class, alpha.getId()); }
    private OrganisationMembership latest(UUID id) { return memberships.findByIdAndOrganisationId(id, alpha.getId()).orElseThrow(); }

    @ParameterizedTest @EnumSource(OrganisationRole.class)
    void directoryIsScopedPaginatedAndSafeForEveryMemberRole(OrganisationRole role) throws Exception {
        var actor = member(alpha, role);
        var foreign = member(beta, OrganisationRole.VIEWER);
        var first = mvc.perform(get(base() + "?size=2&page=0").with(jwt().jwt(t -> t.subject(subject(actor)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].organisationId").value(alpha.getId().toString()))
                .andExpect(jsonPath("$.content[0].userId").isString())
                .andExpect(jsonPath("$.content[0].displayName").value("Demo member"))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[0].identityProviderSubject").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(first.contains(foreign.getId().toString()));
        assertEquals(first, mvc.perform(get(base() + "?size=2&page=0").with(jwt().jwt(t -> t.subject(subject(actor)))))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get(base() + "?size=2&page=2").with(jwt().jwt(t -> t.subject(subject(actor)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.last").value(true)).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get(base() + "?page=99").with(jwt().jwt(t -> t.subject(subject(actor)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        assertEquals(0, auditCount());
    }

    @Test void ownerCanAddPromoteDemoteAndRemoveWithAttributedImmutableAuditReceipts() throws Exception {
        add(owner, candidate.getId(), OrganisationRole.VIEWER).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(candidate.getId().toString()));
        var added = memberships.findByOrganisationIdAndUserId(alpha.getId(), candidate.getId()).orElseThrow();
        change(owner, added.getId(), OrganisationRole.ADMIN).andExpect(status().isOk());
        change(owner, added.getId(), OrganisationRole.ADMIN).andExpect(status().isOk()); // No duplicate audit for a no-op.
        change(owner, added.getId(), OrganisationRole.ENGINEER).andExpect(status().isOk());
        remove(owner, added.getId()).andExpect(status().isNoContent());
        assertTrue(memberships.findByIdAndOrganisationId(added.getId(), alpha.getId()).isEmpty());
        assertEquals(4, auditCount());
        assertEquals(4, jdbc.queryForObject("select count(*) from membership_audit_events where organisation_id=? and actor_user_id=? and target_user_id=? and membership_id=?",
                Long.class, alpha.getId(), owner.getUser().getId(), candidate.getId(), added.getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from membership_audit_events where organisation_id=? and action='ROLE_CHANGED' and previous_role='VIEWER' and new_role='ADMIN'",
                Long.class, alpha.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("update membership_audit_events set action='REMOVED' where organisation_id=?", alpha.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("delete from membership_audit_events where organisation_id=?", alpha.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("truncate membership_audit_events"));
    }

    @ParameterizedTest @EnumSource(OrganisationRole.class)
    void ownerCannotGrantOwnershipThroughOrdinaryMembershipEndpoints(OrganisationRole role) throws Exception {
        add(owner, candidate.getId(), role).andExpect(status().is(role == OrganisationRole.OWNER ? 403 : 201));
    }

    @ParameterizedTest @EnumSource(OrganisationRole.class)
    void adminMayOnlyGrantOperationalOrViewerRoles(OrganisationRole role) throws Exception {
        boolean allowed = role == OrganisationRole.ENGINEER || role == OrganisationRole.VIEWER;
        add(admin, candidate.getId(), role).andExpect(status().is(allowed ? 201 : 403));
        change(admin, engineer.getId(), role).andExpect(status().is(allowed ? 200 : 403));
        assertEquals(allowed ? role : OrganisationRole.ENGINEER, latest(engineer.getId()).getRole());
    }

    @Test void adminCannotModifyOrRemoveOwnerOrAdminIncludingSelfPromotion() throws Exception {
        for (var target : List.of(owner, admin)) {
            change(admin, target.getId(), OrganisationRole.ENGINEER).andExpect(status().isForbidden());
            remove(admin, target.getId()).andExpect(status().isForbidden());
        }
        change(admin, admin.getId(), OrganisationRole.OWNER).andExpect(status().isForbidden());
        change(owner, owner.getId(), OrganisationRole.ADMIN).andExpect(status().isForbidden());
        remove(owner, owner.getId()).andExpect(status().isForbidden());
        remove(admin, engineer.getId()).andExpect(status().isNoContent());
        assertEquals(1, auditCount());
    }

    @ParameterizedTest @EnumSource(value = OrganisationRole.class, names = {"ENGINEER", "VIEWER"})
    void readOnlyManagersCannotAddChangeRemoveOrSelfPromote(OrganisationRole role) throws Exception {
        var actor = role == OrganisationRole.ENGINEER ? engineer : viewer;
        add(actor, candidate.getId(), OrganisationRole.VIEWER).andExpect(status().isForbidden());
        change(actor, actor.getId(), OrganisationRole.ADMIN).andExpect(status().isForbidden());
        remove(actor, engineer.getId()).andExpect(status().isForbidden());
        assertEquals(0, auditCount());
    }

    @Test void foreignAndUnknownMembershipIdsAreIndistinguishableAndNonMembersCannotDiscoverDirectory() throws Exception {
        var foreign = member(beta, OrganisationRole.OWNER);
        for (UUID id : List.of(UUID.randomUUID(), foreign.getId())) {
            change(owner, id, OrganisationRole.VIEWER).andExpect(status().isNotFound());
            remove(owner, id).andExpect(status().isNotFound());
        }
        mvc.perform(get(base()).with(jwt().jwt(t -> t.subject(subject(foreign))))).andExpect(status().isNotFound());
        add(foreign, candidate.getId(), OrganisationRole.VIEWER).andExpect(status().isNotFound());
        mvc.perform(get(base(UUID.randomUUID())).with(jwt().jwt(t -> t.subject(subject(owner))))).andExpect(status().isNotFound());
        assertEquals(0, auditCount());
    }

    @Test void onlyActiveLocallyProvisionedUsersCanBeAddedAndDuplicatesAre409() throws Exception {
        add(owner, UUID.randomUUID(), OrganisationRole.VIEWER).andExpect(status().isNotFound());
        jdbc.update("update users set status='INACTIVE' where id=?", candidate.getId());
        add(owner, candidate.getId(), OrganisationRole.VIEWER).andExpect(status().isNotFound());
        jdbc.update("update users set status='ACTIVE' where id=?", candidate.getId());
        add(owner, candidate.getId(), OrganisationRole.VIEWER).andExpect(status().isCreated());
        add(owner, candidate.getId(), OrganisationRole.VIEWER).andExpect(status().isConflict());
        assertEquals(1, auditCount());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> memberships.save(OrganisationMembership.create(alpha, candidate, OrganisationRole.ENGINEER)));
        jdbc.update("update users set status='INACTIVE' where id=?", owner.getUser().getId());
        mvc.perform(get(base()).with(jwt().jwt(t -> t.subject(subject(owner))))).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"userId\":null,\"role\":\"VIEWER\"}",
            "{\"userId\":\"1-1-1-1-1\",\"role\":\"VIEWER\"}", "{\"userId\":42,\"role\":\"VIEWER\"}",
            "{\"email\":\"nobody@example.test\",\"role\":\"VIEWER\"}"})
    void invalidAddBodiesAre400(String body) throws Exception {
        mvc.perform(post(base()).with(jwt().jwt(t -> t.subject(subject(owner)))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); assertEquals(0, auditCount());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"role\":null}", "{\"role\":0}", "{\"role\":\"SUPERUSER\"}", "{\"role\":\"engineer\"}"})
    void invalidRolesAre400(String body) throws Exception {
        mvc.perform(patch(base() + "/" + engineer.getId()).with(jwt().jwt(t -> t.subject(subject(owner))))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertEquals(0, auditCount());
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483648", "size=invalid"})
    void invalidPaginationIs400(String params) throws Exception {
        mvc.perform(get(base() + "?" + params).with(jwt().jwt(t -> t.subject(subject(owner))))).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "POST", "PATCH", "DELETE"})
    void missingAndInvalidJwtAreRejectedForAllOperations(String method) throws Exception {
        for (String token : new String[]{null, "Bearer invalid"}) {
            var request = switch (method) {
                case "GET" -> get(base());
                case "POST" -> post(base()).content("{\"userId\":\"" + candidate.getId() + "\",\"role\":\"VIEWER\"}");
                case "PATCH" -> patch(base() + "/" + viewer.getId()).content("{\"role\":\"ENGINEER\"}");
                default -> delete(base() + "/" + viewer.getId());
            };
            request.contentType(MediaType.APPLICATION_JSON); if (token != null) request.header("Authorization", token);
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertEquals(0, auditCount());
    }

    @Test void removingMembershipUnassignsIncidentAtomicallyAndRetainsHistoricalHumanAuthorship() throws Exception {
        var incident = incidents.save(Incident.open(alpha.getId(), "Assigned fixture", "Details", AlertSeverity.HIGH, Instant.now(), engineer));
        var note = events.save(IncidentEvent.comment(incident, engineer, Instant.now(), "Historical human comment", null));
        remove(owner, engineer.getId()).andExpect(status().isNoContent());
        var latest = incidents.findByIdAndOrganisationId(incident.getId(), alpha.getId()).orElseThrow();
        assertNull(latest.getAssignedUserId()); assertEquals(1, latest.getVersion());
        assertEquals(1, jdbc.queryForObject("select count(*) from incident_events where id=? and actor_user_id=?", Long.class, note.getId(), engineer.getUser().getId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from incident_events where incident_id=? and event_type='ASSIGNMENT_CHANGED' and actor_user_id is null and previous_assignee_user_id=? and new_assignee_user_id is null",
                Long.class, incident.getId(), engineer.getUser().getId()));
        assertEquals(1, auditCount());
    }

    @Test void auditFailureRollsBackRemovalAndIncidentUnassignment() {
        var incident = incidents.save(Incident.open(alpha.getId(), "Rollback fixture", "Details", AlertSeverity.HIGH, Instant.now(), engineer));
        jdbc.execute("create function reject_test_membership_audit() returns trigger language plpgsql as $$ begin raise exception 'test audit failure'; end; $$");
        jdbc.execute("create trigger reject_test_membership_audit before insert on membership_audit_events for each row execute function reject_test_membership_audit()");
        try {
            assertThrows(RuntimeException.class, () -> application.deleteMembership(subject(owner), alpha.getId(), engineer.getId()));
            assertTrue(memberships.findByIdAndOrganisationId(engineer.getId(), alpha.getId()).isPresent());
            assertEquals(engineer.getUser().getId(), incidents.findByIdAndOrganisationId(incident.getId(), alpha.getId()).orElseThrow().getAssignedUserId());
            assertEquals(0, jdbc.queryForObject("select count(*) from incident_events where incident_id=?", Long.class, incident.getId()));
            assertEquals(0, auditCount());
        } finally {
            jdbc.execute("drop trigger reject_test_membership_audit on membership_audit_events");
            jdbc.execute("drop function reject_test_membership_audit()");
        }
    }

    @Test void concurrentDuplicateAddsHaveOneWinnerAndOneAuditReceipt() throws Exception {
        var result = concurrently(() -> {
            try { application.addMembership(subject(owner), alpha.getId(), candidate.getId(), OrganisationRole.ENGINEER); return "added"; }
            catch (OrganisationMembershipAlreadyExistsException expected) { return "duplicate"; }
        });
        assertEquals(Set.of("added", "duplicate"), new HashSet<>(result)); assertEquals(1, auditCount());
        assertEquals(1, memberships.findAllByOrganisationId(alpha.getId()).stream().filter(m -> m.getUser().getId().equals(candidate.getId())).count());
    }

    @Test void concurrentAttemptsCannotDemoteOrRemoveTheOnlyOwner() throws Exception {
        var results = concurrently(() -> {
            try { application.deleteMembership(subject(owner), alpha.getId(), owner.getId()); return "removed"; }
            catch (InsufficientOrganisationPermissionException expected) { return "protected"; }
        });
        assertEquals(List.of("protected", "protected"), results);
        assertEquals(OrganisationRole.OWNER, latest(owner.getId()).getRole()); assertEquals(0, auditCount());
    }

    @Test void waitingAdminRequestRechecksRoleAfterConcurrentDemotion() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                organisations.lockMembershipAdministration(alpha.getId()).orElseThrow();
                var started = new CountDownLatch(1);
                var waiting = executor.submit(() -> {
                    started.countDown();
                    assertThrows(InsufficientOrganisationPermissionException.class,
                            () -> application.addMembership(subject(admin), alpha.getId(), candidate.getId(), OrganisationRole.VIEWER));
                });
                try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                // Hold the real parent lock while demoting. The queued request must read authorization after it acquires that lock.
                application.updateMembership(subject(owner), alpha.getId(), admin.getId(), OrganisationRole.VIEWER);
                pending = waiting;
            });
            pending.get(15, TimeUnit.SECONDS); assertEquals(1, auditCount());
            assertTrue(memberships.findByOrganisationIdAndUserId(alpha.getId(), candidate.getId()).isEmpty());
        } finally { executor.shutdownNow(); }
    }
    private Future<?> pending;
    private <T> List<T> concurrently(Callable<T> operation) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<T> task = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return operation.call(); };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test void latestMigrationAppliesAndHibernateValidatesAuditSchema() {
        flyway.validate(); assertEquals("13", flyway.info().current().getVersion().getVersion());
        assertEquals(0, auditCount());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into membership_audit_events(id,organisation_id,membership_id,target_user_id,actor_user_id,action,previous_role,new_role,occurred_at) values (?,?,?,?,?,'ROLE_CHANGED','VIEWER','VIEWER',now())",
                UUID.randomUUID(), alpha.getId(), viewer.getId(), viewer.getUser().getId(), owner.getUser().getId()));
    }
}
