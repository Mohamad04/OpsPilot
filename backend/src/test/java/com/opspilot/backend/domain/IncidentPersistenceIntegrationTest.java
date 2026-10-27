package com.opspilot.backend.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IncidentPersistenceIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant START = Instant.parse("2026-10-27T12:00:00.123456Z");
    @Autowired IncidentRepository incidents;
    @Autowired IncidentAlertRepository links;
    @Autowired IncidentEventRepository events;
    @Autowired TestEntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    private Organisation alpha, beta;
    private OrganisationMembership member, foreignMember;

    @BeforeEach void fixtures() {
        alpha = em.persist(Organisation.create("Incident Alpha", "incident-alpha"));
        beta = em.persist(Organisation.create("Incident Beta", "incident-beta"));
        member = membership(alpha, "alpha");
        foreignMember = membership(beta, "beta");
        em.flush();
    }

    private OrganisationMembership membership(Organisation organisation, String name) {
        var user = em.persist(User.create("incident-" + name, name, name + "@incident.example.test"));
        return em.persist(OrganisationMembership.create(organisation, user, OrganisationRole.ENGINEER));
    }

    private Incident incident(Organisation organisation, OrganisationMembership assignee) {
        return incidents.save(Incident.open(organisation.getId(), "HTTP incident", "Investigate repeated failures",
                AlertSeverity.HIGH, START, assignee));
    }

    private Alert alert(Organisation organisation) {
        var service = em.persist(MonitoredService.create(organisation, "Incident HTTP fixture", ServiceType.HTTP,
                Environment.TEST, "https://incident.example.test", "/health", "Incident test"));
        var evidence = em.persist(new HealthCheckResult(organisation.getId(), service.getId(), START,
                START.plusMillis(4), HealthStatus.DOWN, 503, 4, "Unexpected HTTP status: 503."));
        return em.persist(Alert.open(evidence, evidence.getId(), START, 3, 3, 2, AlertSeverity.HIGH));
    }

    private IncidentEvent event(Incident incident, OrganisationMembership actor) {
        return events.save(IncidentEvent.record(incident, IncidentEventType.CREATED, actor, START, "Incident opened"));
    }

    // Savepoints let us verify preserved rows after PostgreSQL rejects a statement.
    private void rejected(String state, String sql, Object... parameters) {
        em.flush();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
                SQLException failure = assertThrows(SQLException.class, statement::executeUpdate);
                assertEquals(state, failure.getSQLState());
            } finally {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
            return null;
        });
    }

    @Test void flywayAndHibernateValidateTheCompleteSchema() {
        flyway.validate();
        assertEquals("9", flyway.info().current().getVersion().getVersion());
        assertEquals("validate", em.getEntityManager().getEntityManagerFactory().getProperties().get("hibernate.hbm2ddl.auto"));
        for (String table : new String[]{"incidents", "incident_alerts", "incident_events"}) {
            assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name=?", Integer.class, table));
        }
    }

    @Test void persistsAndRetrievesIncidentAndOptionalAssignee() {
        var incident = incident(alpha, member);
        UUID id = incident.getId();
        em.flush(); em.clear();
        var loaded = incidents.findByIdAndOrganisationId(id, alpha.getId()).orElseThrow();
        assertEquals("HTTP incident", loaded.getTitle());
        assertEquals("Investigate repeated failures", loaded.getDescription());
        assertEquals(IncidentStatus.OPEN, loaded.getStatus());
        assertEquals(AlertSeverity.HIGH, loaded.getSeverity());
        assertEquals(member.getUser().getId(), loaded.getAssignedUserId());
        assertEquals(START, loaded.getCreatedAt());
        assertNull(loaded.getAcknowledgedAt()); assertNull(loaded.getResolvedAt());
        assertTrue(incidents.findByIdAndOrganisationId(id, beta.getId()).isEmpty());
    }

    @Test void organisationPagesAreScopedAndPagedInTheDatabase() {
        var first = incident(alpha, null); incident(alpha, null); incident(beta, foreignMember);
        em.flush(); em.clear();
        var page = incidents.findAllByOrganisationId(alpha.getId(), PageRequest.of(0, 1,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        assertEquals(2, page.getTotalElements()); assertEquals(1, page.getContent().size());
        assertTrue(page.getContent().stream().allMatch(i -> i.getOrganisationId().equals(alpha.getId())));
        assertEquals(1, incidents.findAllByOrganisationId(beta.getId(), PageRequest.of(0, 20)).getTotalElements());
        assertTrue(incidents.findByIdAndOrganisationId(first.getId(), UUID.randomUUID()).isEmpty());
    }

    @Test void linksMultipleAlertsWithoutChangingTheirLifecycle() {
        var incident = incident(alpha, null);
        var first = alert(alpha); var second = alert(alpha);
        var link = links.save(IncidentAlert.link(incident, first));
        links.save(IncidentAlert.link(incident, second));
        em.flush(); em.clear();
        var history = links.findAllByOrganisationIdAndIncidentIdOrderByIdAsc(alpha.getId(), incident.getId(), PageRequest.of(0, 20));
        assertEquals(2, history.getNumberOfElements());
        assertTrue(links.findByIdAndOrganisationId(link.getId(), beta.getId()).isEmpty());
        assertTrue(links.findAllByOrganisationIdAndIncidentIdOrderByIdAsc(beta.getId(), incident.getId(), PageRequest.of(0, 20)).isEmpty());
        assertEquals(AlertStatus.ACTIVE, em.find(Alert.class, first.getId()).getStatus());
        assertEquals(3, em.find(Alert.class, first.getId()).getOccurrenceCount());
    }

    @Test void databaseRejectsDuplicateIncidentAlertPair() {
        var incident = incident(alpha, null); var alert = alert(alpha);
        links.save(IncidentAlert.link(incident, alert)); em.flush();
        rejected("23505", "insert into incident_alerts(id,organisation_id,incident_id,alert_id) values (?,?,?,?)",
                UUID.randomUUID(), alpha.getId(), incident.getId(), alert.getId());
        assertEquals(1L, jdbc.queryForObject("select count(*) from incident_alerts", Long.class));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void compositeKeysRejectEitherForeignIncidentOrForeignAlert(boolean foreignIncident) {
        var incident = incident(foreignIncident ? beta : alpha, null);
        var alert = alert(foreignIncident ? alpha : beta);
        rejected("23503", "insert into incident_alerts(id,organisation_id,incident_id,alert_id) values (?,?,?,?)",
                UUID.randomUUID(), alpha.getId(), incident.getId(), alert.getId());
    }

    @Test void assignedUserMustBeAnExistingOrganisationMemberEvenForDirectSql() {
        var incident = incident(alpha, null);
        rejected("23503", "update incidents set assigned_user_id=? where id=?", foreignMember.getUser().getId(), incident.getId());
        rejected("23503", "update incidents set assigned_user_id=? where id=?", UUID.randomUUID(), incident.getId());
    }

    @ParameterizedTest @ValueSource(strings = {
            "title=''", "title=E' \n\t'", "description=''", "description=E' \n\t'",
            "severity='URGENT'", "status='INVALID'", "acknowledged_at=created_at - interval '1 second'",
            "status='ACKNOWLEDGED'", "resolved_at=created_at", "status='RESOLVED'", "status='CLOSED'"})
    void databaseRejectsInvalidIncidentData(String assignment) {
        var incident = incident(alpha, null);
        rejected("23514", "update incidents set " + assignment + " where id=?", incident.getId());
    }

    @ParameterizedTest @EnumSource(IncidentStatus.class)
    void schemaCanRepresentAllFutureLifecycleStatusesWithoutImplementingTransitions(IncidentStatus status) {
        var incident = incident(alpha, null); em.flush();
        boolean acknowledged = status != IncidentStatus.OPEN;
        boolean resolved = status == IncidentStatus.RESOLVED || status == IncidentStatus.CLOSED;
        jdbc.update("update incidents set status=?, acknowledged_at=" + (acknowledged ? "created_at" : "null")
                + ", resolved_at=" + (resolved ? "created_at" : "null") + " where id=?", status.name(), incident.getId());
        em.clear();
        assertEquals(status, incidents.findByIdAndOrganisationId(incident.getId(), alpha.getId()).orElseThrow().getStatus());
    }

    @Test void eventsPersistWithOptionalActorsAndScopedTimelinePagination() {
        var incident = incident(alpha, null);
        var event = event(incident, member);
        events.save(IncidentEvent.record(incident, IncidentEventType.COMMENT_ADDED, null, START.plusSeconds(1), "System context"));
        event(incident(beta, null), foreignMember);
        em.flush(); em.clear();
        var loaded = events.findByIdAndOrganisationId(event.getId(), alpha.getId()).orElseThrow();
        assertEquals(member.getUser().getId(), loaded.getActorUserId());
        assertEquals("Incident opened", loaded.getDetails()); assertEquals(START, loaded.getOccurredAt());
        var page = events.findAllByOrganisationIdAndIncidentIdOrderByOccurredAtAscIdAsc(alpha.getId(), incident.getId(), PageRequest.of(0, 1));
        assertEquals(event.getId(), page.getContent().getFirst().getId()); assertTrue(page.hasNext());
        assertTrue(events.findByIdAndOrganisationId(event.getId(), beta.getId()).isEmpty());
        assertTrue(events.findAllByOrganisationIdAndIncidentIdOrderByOccurredAtAscIdAsc(beta.getId(), incident.getId(), PageRequest.of(0, 20)).isEmpty());
    }

    @Test void foreignEventIncidentIsRejectedByCompositeKey() {
        var incident = incident(beta, null);
        rejected("23503", "insert into incident_events(id,organisation_id,incident_id,event_type,occurred_at,details) values (?,?,?,'CREATED',?::timestamptz,'Context')",
                UUID.randomUUID(), alpha.getId(), incident.getId(), START.toString());
    }

    @Test void foreignOrMissingActorIsRejectedOnAppend() {
        var incident = incident(alpha, null);
        for (UUID actor : new UUID[]{foreignMember.getUser().getId(), UUID.randomUUID()}) {
            rejected("23503", "insert into incident_events(id,organisation_id,incident_id,event_type,actor_user_id,occurred_at,details) values (?,?,?,'CREATED',?,?::timestamptz,'Context')",
                    UUID.randomUUID(), alpha.getId(), incident.getId(), actor, START.toString());
        }
    }

    @Test void membershipRemovalUnassignsWithoutDestroyingAuthorshipOrHistory() {
        var incident = incident(alpha, member); var event = event(incident, member);
        em.flush();
        jdbc.update("delete from organisation_memberships where id=?", member.getId());
        em.clear();
        assertNull(incidents.findByIdAndOrganisationId(incident.getId(), alpha.getId()).orElseThrow().getAssignedUserId());
        assertEquals(member.getUser().getId(), events.findByIdAndOrganisationId(event.getId(), alpha.getId()).orElseThrow().getActorUserId());
        rejected("23503", "insert into incident_events(id,organisation_id,incident_id,event_type,actor_user_id,occurred_at,details) values (?,?,?,'COMMENT_ADDED',?,?::timestamptz,'Context')",
                UUID.randomUUID(), alpha.getId(), incident.getId(), member.getUser().getId(), START.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"BAD_TYPE", "CREATED"})
    void databaseRejectsInvalidEventTypeOrAnEventBeforeCreation(String type) {
        var incident = incident(alpha, null);
        rejected("23514", "insert into incident_events(id,organisation_id,incident_id,event_type,occurred_at,details) values (?,?,?,?,?::timestamptz,'Context')",
                UUID.randomUUID(), alpha.getId(), incident.getId(), type,
                (type.equals("CREATED") ? START.minusSeconds(1) : START).toString());
    }

    @Test void hibernateManagedAndDetachedChangesCannotRewriteAnEvent() {
        var incident = incident(alpha, null); var event = event(incident, null);
        em.flush();
        ReflectionTestUtils.setField(event, "details", "Managed rewrite");
        em.flush(); em.clear();
        var original = events.findByIdAndOrganisationId(event.getId(), alpha.getId()).orElseThrow();
        assertEquals("Incident opened", original.getDetails());
        em.detach(original);
        ReflectionTestUtils.setField(original, "details", "Detached rewrite");
        events.save(original); em.flush(); em.clear();
        assertEquals("Incident opened", events.findByIdAndOrganisationId(event.getId(), alpha.getId()).orElseThrow().getDetails());
    }

    @Test void persistenceModelRejectsEventRemoval() {
        var event = event(incident(alpha, null), null); em.flush();
        assertThrows(IllegalStateException.class, () -> em.remove(event));
    }

    @ParameterizedTest @ValueSource(strings = {
            "update incident_events set details='Rewritten' where id=?",
            "update incident_events set id=id where id=?",
            "delete from incident_events where id=?"})
    void directSqlCannotMutateOrDeleteAnEvent(String sql) {
        var event = event(incident(alpha, null), null);
        rejected("55000", sql, event.getId());
        assertEquals("Incident opened", jdbc.queryForObject("select details from incident_events where id=?", String.class, event.getId()));
    }

    @Test void databaseRejectsTruncateAndParentDeletionAndPreservesEvidence() {
        var incident = incident(alpha, null); var alert = alert(alpha);
        links.save(IncidentAlert.link(incident, alert)); var event = event(incident, null);
        rejected("55000", "truncate incident_events");
        rejected("55000", "truncate incidents cascade");
        rejected("23503", "delete from incidents where id=?", incident.getId());
        rejected("23503", "delete from alerts where id=?", alert.getId());
        assertTrue(events.findByIdAndOrganisationId(event.getId(), alpha.getId()).isPresent());
        assertEquals(AlertStatus.ACTIVE, em.find(Alert.class, alert.getId()).getStatus());
    }

    @Test void repositoriesDoNotExposeGlobalReadOrDeleteOperations() {
        for (Class<?> repository : new Class<?>[]{IncidentRepository.class, IncidentAlertRepository.class, IncidentEventRepository.class}) {
            assertTrue(Arrays.stream(repository.getMethods()).noneMatch(method ->
                    method.getName().startsWith("delete") || method.getName().equals("findById") || method.getName().equals("findAll")));
        }
    }
}
