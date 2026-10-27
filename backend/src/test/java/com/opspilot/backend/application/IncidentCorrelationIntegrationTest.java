package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

// Real store -> evaluator -> correlator -> PostgreSQL. No incident mocks, no mutable audit cleanup.
@SpringBootTest(properties = "opspilot.monitoring.enabled=false")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IncidentCorrelationIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant BASE = Instant.parse("2026-10-27T12:00:00Z");
    @Autowired IncidentCorrelationService correlation;
    @Autowired HealthCheckStore store;
    @Autowired AlertRepository alerts;
    @Autowired IncidentRepository incidents;
    @Autowired IncidentAlertRepository links;
    @Autowired IncidentEventRepository events;
    @Autowired OrganisationRepository organisations;
    @Autowired MonitoredServiceRepository services;
    @Autowired HealthCheckResultRepository results;
    @Autowired AlertEvaluationStateRepository states;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> current = new AtomicReference<>(BASE);
    private Organisation alpha, beta;
    private MonitoredService first, sibling, foreign;

    @BeforeEach void fixtures() {
        // Fresh tenant fixtures per test: never delete/truncate append-only timeline rows.
        alpha = organisations.save(Organisation.create("Correlation Alpha", "corr-alpha-" + UUID.randomUUID()));
        beta = organisations.save(Organisation.create("Correlation Beta", "corr-beta-" + UUID.randomUUID()));
        first = service(alpha); sibling = service(alpha); foreign = service(beta);
        current.set(BASE);
        when(clock.instant()).thenAnswer(call -> current.get());
    }

    private MonitoredService service(Organisation organisation) {
        return services.save(MonitoredService.create(organisation, "Correlation fixture", ServiceType.HTTP,
                Environment.TEST, "https://fixture.example.test", "/health", "Correlation test"));
    }

    private Instant tick() { return current.updateAndGet(at -> at.plusSeconds(1)); }

    private HealthCheckResult evidence(MonitoredService service, HealthStatus status) {
        Instant at = tick();
        return results.save(new HealthCheckResult(service.getOrganisation().getId(), service.getId(), at, at.plusMillis(4),
                status, status == HealthStatus.DOWN ? 503 : 200, 4, null));
    }

    private Alert active(MonitoredService service, AlertSeverity severity) {
        var down = evidence(service, HealthStatus.DOWN);
        return alerts.save(Alert.open(down, down.getId(), down.getStartedAt(), 3, 3, 2, severity));
    }

    private void resolve(Alert alert, MonitoredService service) {
        alert.resolve(evidence(service, HealthStatus.UP), 2); alerts.save(alert);
    }

    private void process(Alert alert) {
        correlation.process(alert.getOrganisationId(), alert.getMonitoredServiceId(), alert.getId());
    }

    private void record(HealthStatus status) {
        Instant at = tick();
        store.record(store.loadTarget(first.getId()).orElseThrow(), new HealthObservation(at, at.plusMillis(4), status,
                status == HealthStatus.DOWN ? Integer.valueOf(503) : status == HealthStatus.UP ? Integer.valueOf(200) : null, 4, null));
    }

    private Incident incidentFor(Alert alert) {
        var link = links.findFirstByOrganisationIdAndAlertIdOrderByIdAsc(alert.getOrganisationId(), alert.getId()).orElseThrow();
        return incidents.findByIdAndOrganisationId(link.getIncidentId(), alert.getOrganisationId()).orElseThrow();
    }

    private List<IncidentEvent> timeline(Incident incident) {
        return events.findAllByOrganisationIdAndIncidentIdOrderByOccurredAtAscIdAsc(
                incident.getOrganisationId(), incident.getId(), org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
    }

    private long count(String table, Organisation organisation) {
        // Only constant test table names are passed; every assertion remains tenant-scoped.
        return jdbc.queryForObject("select count(*) from " + table + " where organisation_id=?", Long.class, organisation.getId());
    }

    @Test void phase4aAndV10MigrateAndValidateWithoutBackfilling() {
        flyway.validate(); assertEquals("10", flyway.info().current().getVersion().getVersion());
        assertEquals(0, count("incidents", alpha)); assertEquals(0, count("incident_events", alpha));
        assertEquals(0, count("incident_alerts", alpha));
    }

    @Test void threeFailuresCreateOneIncidentAndRepeatedFailuresOrReplaysAreIdempotent() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        assertEquals(0, count("incidents", alpha)); assertEquals(0, count("alerts", alpha));
        record(HealthStatus.DOWN);
        var alert = alerts.findByOrganisationIdAndMonitoredServiceIdAndCategoryAndStatus(alpha.getId(), first.getId(),
                AlertCategory.HTTP_AVAILABILITY, AlertStatus.ACTIVE).orElseThrow();
        var incident = incidentFor(alert);
        assertEquals(IncidentStatus.OPEN, incident.getStatus()); assertEquals(AlertSeverity.HIGH, incident.getSeverity());
        assertEquals(first.getId(), incident.getMonitoredServiceId()); assertNull(incident.getAssignedUserId());
        assertEquals(2, timeline(incident).size());
        assertEquals(1, timeline(incident).stream().filter(e -> e.getEventType() == IncidentEventType.CREATED).count());
        assertEquals(1, timeline(incident).stream().filter(e -> e.getEventType() == IncidentEventType.ALERT_LINKED).count());
        timeline(incident).forEach(event -> { assertNull(event.getActorUserId()); assertEquals(alert.getId(), event.getSourceAlertId()); });
        record(HealthStatus.DOWN); process(alert); process(alert);
        assertEquals(1, count("incidents", alpha)); assertEquals(1, count("incident_alerts", alpha));
        assertEquals(2, count("incident_events", alpha));
        assertEquals(4, alerts.findByIdAndOrganisationId(alert.getId(), alpha.getId()).orElseThrow().getOccurrenceCount());
    }

    @Test void twoRecoveriesPreserveOpenIncidentAndAppendOneRecoveryEvidenceNote() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var alert = alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(alpha.getId(), first.getId()).getFirst();
        var incidentId = incidentFor(alert).getId();
        record(HealthStatus.UP); assertEquals(2, count("incident_events", alpha));
        record(HealthStatus.UNKNOWN); record(HealthStatus.UP);
        assertEquals(AlertStatus.ACTIVE, alerts.findByIdAndOrganisationId(alert.getId(), alpha.getId()).orElseThrow().getStatus());
        record(HealthStatus.UP);
        var resolved = alerts.findByIdAndOrganisationId(alert.getId(), alpha.getId()).orElseThrow();
        process(resolved); process(resolved);
        var incident = incidentFor(resolved);
        assertEquals(incidentId, incident.getId()); assertEquals(IncidentStatus.OPEN, incident.getStatus()); assertNull(incident.getResolvedAt());
        assertEquals(AlertStatus.RESOLVED, resolved.getStatus()); assertEquals(3, timeline(incident).size());
        var note = timeline(incident).stream().filter(e -> e.getEventType() == IncidentEventType.COMMENT_ADDED).findFirst().orElseThrow();
        assertEquals(resolved.getId(), note.getSourceAlertId()); assertNull(note.getActorUserId());
        assertTrue(note.getDetails().contains(resolved.getResolutionHealthCheckResultId().toString()));
    }

    @ParameterizedTest @EnumSource(value = IncidentStatus.class, names = {"OPEN", "ACKNOWLEDGED", "INVESTIGATING", "MITIGATED"})
    void laterOutageReusesAnOngoingIncidentAndEscalatesWithoutDowngrading(IncidentStatus status) {
        var low = active(first, AlertSeverity.LOW); process(low); var incident = incidentFor(low);
        jdbc.update("update incidents set status=?,acknowledged_at=" + (status == IncidentStatus.OPEN ? "null" : "created_at") + " where id=?",
                status.name(), incident.getId());
        resolve(low, first); process(low);
        var critical = active(first, AlertSeverity.CRITICAL); process(critical);
        assertEquals(incident.getId(), incidentFor(critical).getId()); assertEquals(AlertSeverity.CRITICAL, incidentFor(critical).getSeverity());
        resolve(critical, first); process(critical);
        var medium = active(first, AlertSeverity.MEDIUM); process(medium);
        assertEquals(incident.getId(), incidentFor(medium).getId()); assertEquals(AlertSeverity.CRITICAL, incidentFor(medium).getSeverity());
        assertEquals(status, incidentFor(medium).getStatus()); assertEquals(1, count("incidents", alpha));
        assertEquals(3, count("incident_alerts", alpha)); assertEquals(6, count("incident_events", alpha));
    }

    @Test void completeSecondOutageCreatesANewAlertWithinTheSameUnresolvedIncident() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var firstAlert = alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(alpha.getId(), first.getId()).getFirst();
        var incident = incidentFor(firstAlert);
        record(HealthStatus.UP); record(HealthStatus.UP);
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var secondAlert = alerts.findByOrganisationIdAndMonitoredServiceIdAndCategoryAndStatus(alpha.getId(), first.getId(),
                AlertCategory.HTTP_AVAILABILITY, AlertStatus.ACTIVE).orElseThrow();
        assertNotEquals(firstAlert.getId(), secondAlert.getId()); assertEquals(incident.getId(), incidentFor(secondAlert).getId());
        assertEquals(AlertStatus.RESOLVED, alerts.findByIdAndOrganisationId(firstAlert.getId(), alpha.getId()).orElseThrow().getStatus());
        assertEquals(IncidentStatus.OPEN, incidentFor(secondAlert).getStatus());
        assertEquals(2, count("alerts", alpha)); assertEquals(1, count("incidents", alpha));
        assertEquals(2, count("incident_alerts", alpha)); assertEquals(4, count("incident_events", alpha));
    }

    @ParameterizedTest @EnumSource(value = IncidentStatus.class, names = {"RESOLVED", "CLOSED"})
    void replayNeverReopensFinishedIncidentButANewAlertCreatesANewIncident(IncidentStatus status) {
        var old = active(first, AlertSeverity.HIGH); process(old); var incident = incidentFor(old);
        jdbc.update("update incidents set status=?,acknowledged_at=created_at,resolved_at=created_at where id=?", status.name(), incident.getId());
        process(old); assertEquals(1, count("incidents", alpha)); assertEquals(status, incidentFor(old).getStatus());
        resolve(old, first); process(old);
        var next = active(first, AlertSeverity.LOW); process(next);
        assertNotEquals(incident.getId(), incidentFor(next).getId());
        assertEquals(status, incidentFor(old).getStatus()); assertEquals(IncidentStatus.OPEN, incidentFor(next).getStatus());
        assertEquals(2, count("incidents", alpha)); assertEquals(5, count("incident_events", alpha));
    }

    @Test void differentServicesAndOrganisationsNeverCorrelate() {
        var one = active(first, AlertSeverity.HIGH); var two = active(sibling, AlertSeverity.HIGH); var three = active(foreign, AlertSeverity.HIGH);
        process(one); process(two); process(three);
        assertNotEquals(incidentFor(one).getId(), incidentFor(two).getId());
        assertNotEquals(incidentFor(one).getId(), incidentFor(three).getId());
        assertEquals(2, count("incidents", alpha)); assertEquals(1, count("incidents", beta));
        assertTrue(incidents.findByIdAndOrganisationId(incidentFor(three).getId(), alpha.getId()).isEmpty());
    }

    @Test void foreignAlertForeignServiceAndSiblingServiceAreRejectedWithoutWrites() {
        var foreignAlert = active(foreign, AlertSeverity.HIGH); var local = active(first, AlertSeverity.HIGH);
        assertThrows(IllegalArgumentException.class, () -> correlation.process(alpha.getId(), foreign.getId(), foreignAlert.getId()));
        assertThrows(IllegalArgumentException.class, () -> correlation.process(alpha.getId(), first.getId(), foreignAlert.getId()));
        assertThrows(IllegalArgumentException.class, () -> correlation.process(alpha.getId(), sibling.getId(), local.getId()));
        assertEquals(0, count("incidents", alpha)); assertEquals(0, count("incidents", beta));
        assertEquals(0, count("incident_events", alpha)); assertEquals(0, count("incident_alerts", alpha));
    }

    @Test void resolutionWithoutAnIncidentDoesNotCreateHistoricalIncident() {
        var alert = active(first, AlertSeverity.HIGH); resolve(alert, first); process(alert);
        assertEquals(0, count("incidents", alpha)); assertEquals(0, count("incident_events", alpha));
    }

    @Test void existingUnanchoredPhase4aAssociationIsRespectedRatherThanDuplicated() {
        var alert = active(first, AlertSeverity.HIGH);
        var manual = incidents.save(Incident.open(alpha.getId(), "Existing incident", "Existing Phase 4A association",
                AlertSeverity.CRITICAL, current.get(), null));
        links.save(IncidentAlert.link(manual, alert));
        process(alert); process(alert);
        assertEquals(manual.getId(), incidentFor(alert).getId()); assertNull(incidentFor(alert).getMonitoredServiceId());
        assertEquals(AlertSeverity.CRITICAL, incidentFor(alert).getSeverity());
        assertEquals(1, count("incidents", alpha)); assertEquals(1, count("incident_events", alpha));
    }

    @Test void concurrentProcessesBlockOnParentAndCreateOneIncidentAssociationAndTimeline() throws Exception {
        var alert = active(first, AlertSeverity.HIGH);
        var pool = Executors.newFixedThreadPool(5);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var ready = new CountDownLatch(4); var start = new CountDownLatch(1);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                services.findByIdAndOrganisationIdForUpdate(first.getId(), alpha.getId()).orElseThrow(); held.countDown();
                await(release);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var pending = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) pending.add(pool.submit(() -> { ready.countDown(); await(start); process(alert); }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); int waiting = 0;
            while (waiting < 2 && System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%monitored_services%'", Integer.class);
                if (waiting < 2) Thread.sleep(20);
            }
            assertTrue(waiting >= 2, "Concurrent consumers must actually wait on the service lock");
            release.countDown(); holder.get(10, TimeUnit.SECONDS);
            for (var future : pending) future.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); start.countDown(); pool.shutdownNow(); }
        assertEquals(1, count("incidents", alpha)); assertEquals(1, count("incident_alerts", alpha));
        assertEquals(2, count("incident_events", alpha));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test latch timed out"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    @Test void eventFailureRollsBackHealthEvidenceAlertCountersIncidentAndLinkTogether() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var before = states.findByOrganisationIdAndMonitoredServiceId(alpha.getId(), first.getId()).orElseThrow();
        jdbc.execute("alter table incident_events add constraint fixture_reject_incident_event check (organisation_id <> '" + alpha.getId() + "'::uuid)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> record(HealthStatus.DOWN));
            assertEquals(2, count("health_check_results", alpha)); assertEquals(0, count("alerts", alpha));
            assertEquals(0, count("incidents", alpha)); assertEquals(0, count("incident_alerts", alpha)); assertEquals(0, count("incident_events", alpha));
            var after = states.findByOrganisationIdAndMonitoredServiceId(alpha.getId(), first.getId()).orElseThrow();
            assertEquals(2, after.getConsecutiveFailures()); assertEquals(before.getLastProcessedHealthCheckResultId(), after.getLastProcessedHealthCheckResultId());
        } finally { jdbc.execute("alter table incident_events drop constraint fixture_reject_incident_event"); }
        record(HealthStatus.DOWN);
        assertEquals(1, count("alerts", alpha)); assertEquals(1, count("incidents", alpha)); assertEquals(2, count("incident_events", alpha));
    }

    @Test void failedAttachmentRollsBackSeverityEscalationAndCanBeRetried() {
        var low = active(first, AlertSeverity.LOW); process(low); resolve(low, first); process(low);
        var critical = active(first, AlertSeverity.CRITICAL);
        jdbc.execute("alter table incident_events add constraint fixture_reject_link_event check (source_alert_id is distinct from '" + critical.getId() + "'::uuid)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> process(critical));
            assertEquals(AlertSeverity.LOW, incidentFor(low).getSeverity());
            assertTrue(links.findFirstByOrganisationIdAndAlertIdOrderByIdAsc(alpha.getId(), critical.getId()).isEmpty());
            assertEquals(3, count("incident_events", alpha));
        } finally { jdbc.execute("alter table incident_events drop constraint fixture_reject_link_event"); }
        process(critical); process(critical);
        assertEquals(AlertSeverity.CRITICAL, incidentFor(critical).getSeverity()); assertEquals(4, count("incident_events", alpha));
    }

    @Test void failedRecoveryEvidenceRollsBackAlertResolutionAndItsHealthObservation() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.UP);
        var alert = alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(alpha.getId(), first.getId()).getFirst();
        jdbc.execute("alter table incident_events add constraint fixture_reject_recovery check (organisation_id <> '" + alpha.getId()
                + "'::uuid or event_type <> 'COMMENT_ADDED')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> record(HealthStatus.UP));
            assertEquals(AlertStatus.ACTIVE, alerts.findByIdAndOrganisationId(alert.getId(), alpha.getId()).orElseThrow().getStatus());
            assertEquals(1, states.findByOrganisationIdAndMonitoredServiceId(alpha.getId(), first.getId()).orElseThrow().getConsecutiveRecoveries());
            assertEquals(4, count("health_check_results", alpha)); assertEquals(2, count("incident_events", alpha));
            assertEquals(IncidentStatus.OPEN, incidentFor(alert).getStatus());
        } finally { jdbc.execute("alter table incident_events drop constraint fixture_reject_recovery"); }
        record(HealthStatus.UP);
        assertEquals(AlertStatus.RESOLVED, alerts.findByIdAndOrganisationId(alert.getId(), alpha.getId()).orElseThrow().getStatus());
        assertEquals(3, count("incident_events", alpha)); assertEquals(IncidentStatus.OPEN, incidentFor(alert).getStatus());
    }

    @Test void databaseRejectsDuplicateOngoingIncidentAndTimelineEvent() {
        var alert = active(first, AlertSeverity.HIGH); process(alert); var incident = incidentFor(alert);
        assertThrows(DataIntegrityViolationException.class, () -> incidents.save(Incident.openForAlert(alert, current.get())));
        assertThrows(DataIntegrityViolationException.class, () -> events.save(IncidentEvent.forSystemAlert(incident, alert,
                IncidentEventType.ALERT_LINKED, current.get(), "Duplicate evidence")));
        assertEquals(1, count("incidents", alpha)); assertEquals(2, count("incident_events", alpha));
    }

    @Test void databaseRejectsCrossServiceLinksAndCrossTenantEventEvidence() {
        var alert = active(first, AlertSeverity.HIGH); process(alert); var incident = incidentFor(alert);
        var wrongService = active(sibling, AlertSeverity.HIGH); var wrongTenant = active(foreign, AlertSeverity.HIGH);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into incident_alerts(id,organisation_id,incident_id,alert_id) values (?,?,?,?)",
                UUID.randomUUID(), alpha.getId(), incident.getId(), wrongService.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into incident_events(id,organisation_id,incident_id,event_type,source_alert_id,occurred_at,details) values (?,?,?,'ALERT_LINKED',?,?::timestamptz,'Foreign')",
                UUID.randomUUID(), alpha.getId(), incident.getId(), wrongTenant.getId(), current.get().toString()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("insert into incidents(id,organisation_id,monitored_service_id,title,description,severity,status,created_at) values (?,?,?,'Title','Description','HIGH','OPEN',?::timestamptz)",
                UUID.randomUUID(), alpha.getId(), foreign.getId(), current.get().toString()));
    }

    @Test void changedTargetIsDiscardedBeforeAnyIncidentCreation() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var oldTarget = store.loadTarget(first.getId()).orElseThrow();
        first.changeBaseUrl("https://changed.example.test"); services.save(first);
        Instant at = tick(); store.record(oldTarget, new HealthObservation(at, at.plusMillis(4), HealthStatus.DOWN, 503, 4, null));
        assertEquals(2, count("health_check_results", alpha)); assertEquals(0, count("alerts", alpha)); assertEquals(0, count("incidents", alpha));
    }
}
