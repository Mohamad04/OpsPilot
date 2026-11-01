package com.opspilot.backend.application;

import com.opspilot.backend.application.exception.IncidentConflictException;
import com.opspilot.backend.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
class IncidentCollaborationIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant BASE = Instant.parse("2026-10-28T12:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired IncidentService application;
    @Autowired IncidentRepository incidents;
    @Autowired IncidentEventRepository events;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired UserRepository users;
    @Autowired MonitoredServiceRepository services;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    private Organisation alpha, beta;
    private String owner, engineer;
    private OrganisationMembership ownerMember, engineerMember;
    private Incident incident;

    @BeforeEach void fixtures() {
        alpha = organisations.save(Organisation.create("Collaboration Alpha", "collab-a-" + UUID.randomUUID()));
        beta = organisations.save(Organisation.create("Collaboration Beta", "collab-b-" + UUID.randomUUID()));
        owner = "owner-" + UUID.randomUUID(); engineer = "engineer-" + UUID.randomUUID();
        ownerMember = member(alpha, owner, OrganisationRole.OWNER);
        engineerMember = member(alpha, engineer, OrganisationRole.ENGINEER);
        incident = incidents.save(Incident.open(alpha.getId(), "Collaboration fixture", "Safe details", AlertSeverity.HIGH, BASE.minusSeconds(1), null));
        when(clock.instant()).thenReturn(BASE);
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Invalid test credential"));
    }

    private OrganisationMembership member(Organisation organisation, String subject, OrganisationRole role) {
        var user = users.save(User.create(subject, "Member", subject + "@example.test"));
        return memberships.save(OrganisationMembership.create(organisation, user, role));
    }
    private String path(UUID id) { return "/api/organisations/" + alpha.getId() + "/incidents/" + id; }
    private ResultActions assign(UUID id, UUID assignee, long version, String subject) throws Exception {
        return mvc.perform(patch(path(id) + "/assignee").with(jwt().jwt(token -> token.subject(subject)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeUserId\":" + (assignee == null ? "null" : "\"" + assignee + "\"") + ",\"expectedVersion\":" + version + "}"));
    }
    private ResultActions comment(UUID id, String text, String key, String subject) throws Exception {
        var request = post(path(id) + "/comments").with(jwt().jwt(token -> token.subject(subject)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("content", text)));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }
    private long count() { return jdbc.queryForObject("select count(*) from incident_events where incident_id=?", Long.class, incident.getId()); }
    private Incident latest() { return incidents.findByIdAndOrganisationId(incident.getId(), alpha.getId()).orElseThrow(); }

    @Test void assignmentReassignmentUnassignmentAndNoOpsHaveCorrectVersionsAndMetadata() throws Exception {
        UUID first = engineerMember.getUser().getId(), second = ownerMember.getUser().getId();
        assign(incident.getId(), first, 0, owner).andExpect(status().isOk()).andExpect(jsonPath("$.assignedUserId").value(first.toString())).andExpect(jsonPath("$.version").value(1));
        assign(incident.getId(), first, 1, owner).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)); assertEquals(1, count());
        assign(incident.getId(), second, 1, owner).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        var event = events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow();
        assertEquals(first, event.getPreviousAssigneeUserId()); assertEquals(second, event.getNewAssigneeUserId());
        assertEquals(second, event.getActorUserId()); assertEquals(IncidentEventType.ASSIGNMENT_CHANGED, event.getEventType());
        assign(incident.getId(), null, 2, owner).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3)).andExpect(jsonPath("$.assignedUserId").isEmpty());
        assign(incident.getId(), null, 3, owner).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
        assertEquals(3, count());
        mvc.perform(get(path(incident.getId()) + "/events").with(jwt().jwt(t -> t.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[1].previousAssigneeUserId").value(first.toString()))
                .andExpect(jsonPath("$.content[1].newAssigneeUserId").value(second.toString()));
    }

    @ParameterizedTest @EnumSource(OrganisationRole.class)
    void actualRolesEnforceAssignmentAndCommentPermissions(OrganisationRole role) throws Exception {
        String subject = "role-" + UUID.randomUUID(); var actor = member(alpha, subject, role);
        assign(incident.getId(), actor.getUser().getId(), 0, subject).andExpect(status().is(role == OrganisationRole.VIEWER ? 403 : 200));
        comment(incident.getId(), "Investigation", null, subject).andExpect(status().is(role == OrganisationRole.VIEWER ? 403 : 201));
        assertEquals(role == OrganisationRole.VIEWER ? 0 : 2, count());
    }

    @Test void engineersCanClaimAndReleaseButCannotStealReassignOrReleaseOthers() throws Exception {
        UUID self = engineerMember.getUser().getId(), admin = ownerMember.getUser().getId();
        assign(incident.getId(), admin, 0, engineer).andExpect(status().isForbidden());
        assign(incident.getId(), self, 0, engineer).andExpect(status().isOk());
        assign(incident.getId(), self, 1, engineer).andExpect(status().isOk());
        assign(incident.getId(), null, 1, engineer).andExpect(status().isOk());
        assign(incident.getId(), admin, 2, owner).andExpect(status().isOk());
        assign(incident.getId(), self, 3, engineer).andExpect(status().isForbidden());
        assign(incident.getId(), null, 3, engineer).andExpect(status().isForbidden()); assertEquals(3, count());
    }

    @Test void foreignUnknownInactiveViewerAndFormerMembersAreIndistinguishableAssignees() throws Exception {
        var foreign = member(beta, "foreign-" + UUID.randomUUID(), OrganisationRole.ADMIN);
        var inactive = member(alpha, "inactive-" + UUID.randomUUID(), OrganisationRole.ENGINEER);
        jdbc.update("update users set status='INACTIVE' where id=?", inactive.getUser().getId());
        var viewer = member(alpha, "viewer-" + UUID.randomUUID(), OrganisationRole.VIEWER);
        var former = member(alpha, "former-" + UUID.randomUUID(), OrganisationRole.ENGINEER); memberships.delete(former);
        String error = null;
        for (UUID target : List.of(UUID.randomUUID(), foreign.getUser().getId(), inactive.getUser().getId(), viewer.getUser().getId(), former.getUser().getId())) {
            String response = assign(incident.getId(), target, 0, owner).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            if (error == null) error = response; else assertEquals(error, response);
        }
        assertEquals(0, count()); assertNull(latest().getAssignedUserId());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"expectedVersion\":0}", "{\"assigneeUserId\":null}",
            "{\"assigneeUserId\":null,\"expectedVersion\":null}", "{\"assigneeUserId\":null,\"expectedVersion\":-1}",
            "{\"assigneeUserId\":null,\"expectedVersion\":0.5}", "{\"assigneeUserId\":null,\"expectedVersion\":\"0\"}",
            "{\"assigneeUserId\":null,\"expectedVersion\":9223372036854775808}",
            "{\"assigneeUserId\":42,\"expectedVersion\":0}", "{\"assigneeUserId\":\"1-1-1-1-1\",\"expectedVersion\":0}",
            "{\"assigneeUserId\":\"invalid\",\"expectedVersion\":0}"})
    void assignmentRequiresExplicitNullOrCanonicalUuidAndStrictVersion(String body) throws Exception {
        mvc.perform(patch(path(incident.getId()) + "/assignee").with(jwt().jwt(t -> t.subject(owner))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\" \\n\\t\"}",
            "{\"content\":123}", "{\"content\":true}", "{\"content\":[]}", "{\"content\":\"text\\u0000\"}"})
    void invalidCommentsAre400AndNeverAppended(String body) throws Exception {
        mvc.perform(post(path(incident.getId()) + "/comments").with(jwt().jwt(t -> t.subject(owner))).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); assertEquals(0, count());
    }

    @Test void commentLengthAndIdempotencyKeysAreBounded() throws Exception {
        comment(incident.getId(), "x".repeat(4000), null, owner).andExpect(status().isCreated());
        comment(incident.getId(), "x".repeat(4001), null, owner).andExpect(status().isBadRequest());
        for (String key : List.of("", "invalid", "1-1-1-1-1", "x".repeat(100))) {
            comment(incident.getId(), "Text", key, owner).andExpect(status().isBadRequest());
        }
        assertEquals(1, count());
    }

    @Test void commentsHaveTrustedAuthorsPlainTextAndNeverChangeIncidentVersion() throws Exception {
        String text = "<script>alert('untrusted')</script>\nInvestigation";
        var response = mvc.perform(post(path(incident.getId()) + "/comments").with(jwt().jwt(t -> t.subject(engineer)))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("content", text,
                        "actorUserId", ownerMember.getUser().getId(), "organisationId", beta.getId(), "eventType", "ALERT_RESOLVED"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.actorUserId").value(engineerMember.getUser().getId().toString()))
                .andExpect(jsonPath("$.eventType").value("COMMENT_ADDED")).andExpect(jsonPath("$.sourceAlertId").isEmpty())
                .andExpect(jsonPath("$.details").value(text)).andReturn().getResponse().getContentAsString();
        assertFalse(json.readTree(response).has("clientRequestId")); assertEquals(0, latest().getVersion()); assertEquals(IncidentStatus.OPEN, latest().getStatus());
    }

    @Test void commentRetryIsScopedToActorAndIncidentAndDoesNotSuppressLegitimateRepeats() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = comment(incident.getId(), " Text ", key, owner).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, comment(incident.getId(), "Text", key, owner).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        comment(incident.getId(), "Other content", key, owner).andExpect(status().isConflict()); assertEquals(1, count());
        comment(incident.getId(), "Text", key, engineer).andExpect(status().isCreated());
        comment(incident.getId(), "Text", null, owner).andExpect(status().isCreated()); comment(incident.getId(), "Text", null, owner).andExpect(status().isCreated());
        var another = incidents.save(Incident.open(alpha.getId(), "Other", "Details", AlertSeverity.LOW, BASE, null));
        comment(another.getId(), "Text", key, owner).andExpect(status().isCreated()); assertEquals(4, count());
    }

    @Test void foreignIncidentsNonMembersAndRevokedMembersCannotReadOrMutateCollaborationData() throws Exception {
        var foreign = incidents.save(Incident.open(beta.getId(), "Other", "Details", AlertSeverity.LOW, BASE, null));
        String outsider = "outsider-" + UUID.randomUUID(); member(beta, outsider, OrganisationRole.ADMIN);
        for (UUID id : List.of(foreign.getId(), UUID.randomUUID())) {
            assign(id, null, 0, owner).andExpect(status().isNotFound()); comment(id, "Text", null, owner).andExpect(status().isNotFound());
        }
        assign(incident.getId(), null, 0, outsider).andExpect(status().isNotFound()); comment(incident.getId(), "Text", null, outsider).andExpect(status().isNotFound());
        memberships.delete(engineerMember);
        assign(incident.getId(), null, 0, engineer).andExpect(status().isNotFound()); comment(incident.getId(), "Text", null, engineer).andExpect(status().isNotFound()); assertEquals(0, count());
    }

    @ParameterizedTest @ValueSource(strings = {"assignee", "comments"})
    void missingAndInvalidJwtAre401(String operation) throws Exception {
        for (String token : new String[]{null, "Bearer invalid.token"}) {
            var request = operation.equals("assignee") ? patch(path(incident.getId()) + "/assignee").content("{\"assigneeUserId\":null,\"expectedVersion\":0}")
                    : post(path(incident.getId()) + "/comments").content("{\"content\":\"Text\"}");
            request.contentType(MediaType.APPLICATION_JSON); if (token != null) request.header("Authorization", token);
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertEquals(0, count());
    }

    @ParameterizedTest @EnumSource(IncidentStatus.class)
    void allStatusesAcceptCommentsAndOnlyOngoingStatusesAcceptAssignments(IncidentStatus target) throws Exception {
        if (target != IncidentStatus.OPEN) application.changeStatus(owner, alpha.getId(), incident.getId(), IncidentStatus.ACKNOWLEDGED, 0);
        if (target == IncidentStatus.INVESTIGATING || target == IncidentStatus.MITIGATED) application.changeStatus(owner, alpha.getId(), incident.getId(), IncidentStatus.INVESTIGATING, latest().getVersion());
        if (target == IncidentStatus.MITIGATED) application.changeStatus(owner, alpha.getId(), incident.getId(), IncidentStatus.MITIGATED, latest().getVersion());
        if (target == IncidentStatus.RESOLVED || target == IncidentStatus.CLOSED) application.changeStatus(owner, alpha.getId(), incident.getId(), IncidentStatus.RESOLVED, latest().getVersion());
        if (target == IncidentStatus.CLOSED) application.changeStatus(owner, alpha.getId(), incident.getId(), IncidentStatus.CLOSED, latest().getVersion());
        comment(incident.getId(), "Follow-up", null, owner).andExpect(status().isCreated());
        assign(incident.getId(), engineerMember.getUser().getId(), latest().getVersion(), owner).andExpect(status().is(target.isOngoing() ? 200 : 409));
    }

    @Test void membershipRemovalUnassignsVersionsAndAuditsWithoutErasingHistoricalAuthorship() throws Exception {
        assign(incident.getId(), engineerMember.getUser().getId(), 0, owner).andExpect(status().isOk());
        var response = comment(incident.getId(), "Original author", null, engineer).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        memberships.delete(engineerMember);
        assertNull(latest().getAssignedUserId()); assertEquals(2, latest().getVersion());
        var last = events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow();
        assertNull(last.getActorUserId()); assertEquals(engineerMember.getUser().getId(), last.getPreviousAssigneeUserId()); assertNull(last.getNewAssigneeUserId());
        assertEquals(IncidentEventType.ASSIGNMENT_CHANGED, last.getEventType()); assertEquals(3, count());
        UUID commentId = UUID.fromString(json.readTree(response).get("id").asText());
        assertEquals(engineerMember.getUser().getId(), events.findByIdAndOrganisationId(commentId, alpha.getId()).orElseThrow().getActorUserId());
        assign(incident.getId(), ownerMember.getUser().getId(), 1, owner).andExpect(status().isConflict());
    }

    @Test void timelineFailureRollsBackAssignmentAndCommentAndMembershipRemoval() {
        UUID self = ownerMember.getUser().getId();
        jdbc.execute("alter table incident_events add constraint fixture_reject_collaboration check (organisation_id <> '" + alpha.getId() + "'::uuid)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> application.changeAssignee(owner, alpha.getId(), incident.getId(), self, 0));
            assertThrows(DataIntegrityViolationException.class, () -> application.addComment(owner, alpha.getId(), incident.getId(), "Text", null));
            assertNull(latest().getAssignedUserId()); assertEquals(0, latest().getVersion()); assertEquals(0, count());
        } finally { jdbc.execute("alter table incident_events drop constraint fixture_reject_collaboration"); }
        application.changeAssignee(owner, alpha.getId(), incident.getId(), self, 0);
        jdbc.execute("alter table incident_events add constraint fixture_reject_collaboration check (organisation_id <> '" + alpha.getId() + "'::uuid) not valid");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> memberships.delete(ownerMember));
            assertTrue(memberships.findByOrganisationIdAndUserId(alpha.getId(), self).isPresent());
            assertEquals(self, latest().getAssignedUserId()); assertEquals(1, latest().getVersion()); assertEquals(1, count());
        } finally { jdbc.execute("alter table incident_events drop constraint fixture_reject_collaboration"); }
    }

    @Test void postgresMembershipConflictDuringAssignmentIs409AndRollsBackAllWrites() throws Exception {
        // Controlled fault injection into this disposable tenant reproduces a membership disappearing
        // after eligibility was checked, before PostgreSQL validates the assignment FK.
        jdbc.execute("""
                create function fixture_remove_assignee() returns trigger language plpgsql as $$ begin
                if NEW.organisation_id = '%s'::uuid and NEW.assigned_user_id = '%s'::uuid then
                    delete from organisation_memberships where organisation_id = NEW.organisation_id and user_id = NEW.assigned_user_id;
                end if;
                return NEW; end; $$
                """.formatted(alpha.getId(), engineerMember.getUser().getId()));
        jdbc.execute("create trigger fixture_remove_assignee before update of assigned_user_id on incidents for each row execute function fixture_remove_assignee()");
        try {
            assign(incident.getId(), engineerMember.getUser().getId(), 0, owner).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Incident or membership changed; reload and retry"));
            assertNull(latest().getAssignedUserId()); assertEquals(0, latest().getVersion()); assertEquals(0, count());
            assertTrue(memberships.findByOrganisationIdAndUserId(alpha.getId(), engineerMember.getUser().getId()).isPresent());
        } finally {
            jdbc.execute("drop trigger fixture_remove_assignee on incidents"); jdbc.execute("drop function fixture_remove_assignee()");
        }
    }

    @Test void concurrentAssignmentsWithTheSameVersionHaveOneWinner() throws Exception {
        var codes = concurrently(() -> assign(incident.getId(), engineerMember.getUser().getId(), 0, owner).andReturn().getResponse().getStatus(),
                () -> assign(incident.getId(), ownerMember.getUser().getId(), 0, owner).andReturn().getResponse().getStatus());
        assertEquals(List.of(200, 409), codes.stream().sorted().toList()); assertEquals(1, latest().getVersion()); assertEquals(1, count());
    }

    @Test void concurrentCommentsPreserveEveryRequestAndDeduplicateTheSameKey() throws Exception {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        var ids = concurrently(() -> application.addComment(owner, alpha.getId(), incident.getId(), "Same text", first).id(),
                () -> application.addComment(owner, alpha.getId(), incident.getId(), "Same text", second).id());
        assertNotEquals(ids.get(0), ids.get(1)); assertEquals(2, count());
        var duplicate = concurrently(() -> application.addComment(engineer, alpha.getId(), incident.getId(), "Retry", first).id(),
                () -> application.addComment(engineer, alpha.getId(), incident.getId(), "Retry", first).id());
        assertEquals(duplicate.get(0), duplicate.get(1)); assertEquals(3, count()); assertEquals(0, latest().getVersion());
        var timeline = application.timeline(owner, alpha.getId(), incident.getId(), 0, 20).content();
        for (int i = 1; i < timeline.size(); i++) assertTrue(timeline.get(i).occurredAt().isAfter(timeline.get(i-1).occurredAt()));
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(3); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                incidents.findByIdAndOrganisationIdForUpdate(incident.getId(), alpha.getId()).orElseThrow(); held.countDown(); await(release);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS)); var futures = new ArrayList<Future<T>>();
            for (var task : List.of(first, second)) futures.add(executor.submit(() -> {ready.countDown(); await(start); return task.call();}));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); int waiting = 0;
            while (waiting < 2 && System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%incidents%'", Integer.class);
                if (waiting < 2) Thread.sleep(20);
            }
            assertTrue(waiting >= 2, "Both requests must contend on the persisted incident lock"); release.countDown(); holder.get(10, TimeUnit.SECONDS);
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        } finally {release.countDown(); start.countDown(); executor.shutdownNow();}
    }
    private static void await(CountDownLatch latch) {
        try {if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture latch timeout");}
        catch (InterruptedException exception) {Thread.currentThread().interrupt();throw new IllegalStateException(exception);}
    }

    @Test void migrationSchemaConstraintsAndImmutabilityRemainAuthoritative() throws Exception {
        flyway.validate(); assertEquals("13", flyway.info().current().getVersion().getVersion());
        String key = UUID.randomUUID().toString();
        var event = application.addComment(owner, alpha.getId(), incident.getId(), "Immutable", key);
        assertEquals("55000", assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.update("update incident_events set details='Edited' where id=?", event.id())).getSQLException().getSQLState());
        assertEquals("55000", assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.update("delete from incident_events where id=?", event.id())).getSQLException().getSQLState());
        assertEquals("55000", assertThrows(org.springframework.jdbc.UncategorizedSQLException.class,
                () -> jdbc.execute("truncate incident_events")).getSQLException().getSQLState());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into incident_events(id,organisation_id,incident_id,event_type,actor_user_id,occurred_at,details,client_request_id)
                values (?,?,?,'COMMENT_ADDED',?,?, 'Duplicate',?)
                """, UUID.randomUUID(), alpha.getId(), incident.getId(), ownerMember.getUser().getId(), java.sql.Timestamp.from(BASE), UUID.fromString(key)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into incident_events(id,organisation_id,incident_id,event_type,occurred_at,details,client_request_id)
                values (?,?,?,'COMMENT_ADDED',?,'System cannot use a human retry key',?)
                """, UUID.randomUUID(), alpha.getId(), incident.getId(), java.sql.Timestamp.from(BASE), UUID.randomUUID()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into incident_events(id,organisation_id,incident_id,event_type,actor_user_id,occurred_at,details,previous_assignee_user_id,new_assignee_user_id)
                values (?,?,?,'COMMENT_ADDED',?,?,'Wrong metadata',?,?)
                """, UUID.randomUUID(), alpha.getId(), incident.getId(), ownerMember.getUser().getId(), java.sql.Timestamp.from(BASE), ownerMember.getUser().getId(), engineerMember.getUser().getId()));
    }

    @Test void browserPreflightAllowsTheBoundedCommentIdempotencyHeader() throws Exception {
        mvc.perform(options(path(incident.getId()) + "/comments").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("idempotency-key")));
    }
}
