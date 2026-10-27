package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// Everything is a disposable fixture: V1–V8 PostgreSQL, local HTTP endpoint, JWT decoder and clock.
@SpringBootTest(properties = "opspilot.monitoring.enabled=false")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlertEvaluationIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant BASE = Instant.parse("2026-10-26T12:00:00Z");
    @Autowired AlertEvaluationEngine engine;
    @Autowired AlertRepository alerts;
    @Autowired AlertEvaluationStateRepository states;
    @Autowired AlertRuleRepository rules;
    @Autowired HealthCheckResultRepository results;
    @Autowired MonitoredServiceRepository services;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired UserRepository users;
    @Autowired HealthCheckStore store;
    @Autowired HealthCheckRunner runner;
    @Autowired AlertRuleService ruleActions;
    @Autowired MonitoredServicesService serviceActions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean Clock clock;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean HealthTargetPolicy targetPolicy;
    // Isolate Phase 3 evaluation/cleanup fixtures from the new append-only incident consumer.
    // IncidentCorrelationIntegrationTest exercises the real end-to-end consumer and transaction rollback.
    @MockitoBean IncidentCorrelationService incidentCorrelation;
    private final AtomicReference<Instant> current = new AtomicReference<>(BASE);
    private final AtomicInteger httpStatus = new AtomicInteger(503), hits = new AtomicInteger();
    private int second;
    private Organisation alpha, beta;
    private MonitoredService first, other;
    private HttpServer server;

    @BeforeEach void fixtures() throws Exception {
        alerts.deleteAll(); states.deleteAll(); results.deleteAll(); rules.deleteAll(); services.deleteAll();
        memberships.deleteAll(); organisations.deleteAll(); users.deleteAll();
        current.set(BASE); second = 0;
        when(clock.instant()).thenAnswer(call -> current.get());
        alpha = organisations.save(Organisation.create("Evaluation Alpha", "eval-alpha"));
        beta = organisations.save(Organisation.create("Evaluation Beta", "eval-beta"));
        var owner = users.save(User.create("evaluator-owner", "Evaluator owner", "evaluator@example.test"));
        memberships.save(OrganisationMembership.create(alpha, owner, OrganisationRole.OWNER));
        memberships.save(OrganisationMembership.create(beta, owner, OrganisationRole.OWNER));
        first = create(alpha); other = create(beta);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(httpStatus.get(), -1); exchange.close(); });
        server.start();
        // Fixture-only DNS pinning, never a production private-network exception.
        when(targetPolicy.validate(anyString(), anyString())).thenAnswer(call -> new HealthTargetPolicy.Target(
                URI.create("http://fixture.example.test/health"), "fixture.example.test", server.getAddress().getPort(), InetAddress.getByName("127.0.0.1")));
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }

    private MonitoredService create(Organisation organisation) {
        return services.save(MonitoredService.create(organisation, "Evaluation fixture", ServiceType.HTTP,
                Environment.TEST, "http://fixture.example.test", "/health", "Evaluation team"));
    }
    private Instant tick() { Instant now = BASE.plusSeconds(++second); current.set(now); return now; }
    private HealthCheckResult record(HealthStatus status) {
        Instant started = tick();
        store.record(store.loadTarget(first.getId()).orElseThrow(), observation(status, started));
        return results.findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(alpha.getId(), first.getId()).orElseThrow();
    }
    private HealthObservation observation(HealthStatus status, Instant at) {
        return new HealthObservation(at, at.plusMillis(25), status,
                status == HealthStatus.DOWN ? Integer.valueOf(503) : status == HealthStatus.UP ? Integer.valueOf(200) : null, 25,
                status == HealthStatus.DOWN ? "Unexpected HTTP status: 503." : status == HealthStatus.UNKNOWN ? "Internal monitoring failure." : null);
    }
    private HealthCheckResult persist(MonitoredService service, HealthStatus status, Instant at) {
        var observation = observation(status, at);
        return results.save(new HealthCheckResult(service.getOrganisation().getId(), service.getId(), at,
                observation.completedAt(), status, observation.httpStatusCode(), 25, observation.failureDetail()));
    }
    private void evaluate(HealthCheckResult result) { engine.evaluate(result.getOrganisationId(), result.getMonitoredServiceId(), result.getId()); }
    private AlertEvaluationState state() { return states.findByOrganisationIdAndMonitoredServiceId(alpha.getId(), first.getId()).orElseThrow(); }
    private Alert active() { return alerts.findByOrganisationIdAndMonitoredServiceIdAndCategoryAndStatus(
            alpha.getId(), first.getId(), AlertCategory.HTTP_AVAILABILITY, AlertStatus.ACTIVE).orElseThrow(); }
    private void configure(boolean enabled, int failures, int recoveries, AlertSeverity severity) {
        tick(); ruleActions.configureRule("evaluator-owner", alpha.getId(), first.getId(), enabled, failures, recoveries, severity);
    }
    private void outage() { record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.DOWN); }

    @Test void defaultLifecyclePersistsCountersEvidenceAndDistinctOutageHistory() {
        record(HealthStatus.UP);
        var firstFailure = record(HealthStatus.DOWN); assertEquals(1, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        record(HealthStatus.DOWN); assertEquals(2, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        var trigger = record(HealthStatus.DOWN); var alert = active(); UUID id = alert.getId();
        assertEquals(3, alert.getOccurrenceCount()); assertEquals(AlertSeverity.HIGH, alert.getSeverity());
        assertEquals(firstFailure.getStartedAt(), alert.getFirstSeenAt()); assertEquals(trigger.getStartedAt(), alert.getLastSeenAt());
        assertEquals(trigger.getId(), alert.getTriggeringHealthCheckResultId()); assertEquals(firstFailure.getId(), alert.getFirstFailureHealthCheckResultId());
        assertEquals(503, alert.getTriggeringHttpStatusCode()); assertEquals(25, alert.getTriggeringResponseTimeMs());
        assertEquals("Unexpected HTTP status: 503.", alert.getTriggeringFailureDetail());
        var further = record(HealthStatus.DOWN); assertEquals(id, active().getId()); assertEquals(4, active().getOccurrenceCount());
        assertEquals(further.getId(), active().getLastFailureHealthCheckResultId());
        record(HealthStatus.UP); assertEquals(1, state().getConsecutiveRecoveries()); assertEquals(AlertStatus.ACTIVE, active().getStatus());
        var resolution = record(HealthStatus.UP); var resolved = alerts.findById(id).orElseThrow();
        assertEquals(AlertStatus.RESOLVED, resolved.getStatus()); assertEquals(resolution.getId(), resolved.getResolutionHealthCheckResultId());
        assertEquals(resolution.getStartedAt(), resolved.getResolvedAt()); assertEquals(2, resolved.getResolvedAfterRecoveries());
        record(HealthStatus.DOWN); assertEquals(1, state().getConsecutiveFailures()); assertEquals(1, alerts.count());
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        assertNotEquals(id, active().getId()); assertEquals(2, alerts.count());
        assertEquals(AlertStatus.RESOLVED, alerts.findById(id).orElseThrow().getStatus());
        assertEquals(0, rules.count()); // Effective defaults never create an AlertRule.
    }

    @Test void runnerAutomaticallyEvaluatesControlledHttpResults() {
        for (int i = 0; i < 3; i++) { tick(); runner.check(first.getId()); }
        assertEquals(3, hits.get()); assertEquals(3, results.count()); assertEquals(1, alerts.count());
        UUID id = active().getId(); httpStatus.set(200);
        for (int i = 0; i < 2; i++) { tick(); runner.check(first.getId()); }
        assertEquals(AlertStatus.RESOLVED, alerts.findById(id).orElseThrow().getStatus());
        assertEquals(5, results.count());
    }

    @Test void unknownInterruptsBothStreaksAndNeitherTriggersNorResolves() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.UNKNOWN);
        assertEquals(0, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        outage(); UUID id = active().getId(); long occurrences = active().getOccurrenceCount();
        record(HealthStatus.UP); record(HealthStatus.UNKNOWN);
        assertEquals(0, state().getConsecutiveRecoveries()); assertEquals(id, active().getId());
        assertEquals(occurrences, active().getOccurrenceCount());
        record(HealthStatus.UP); assertEquals(id, active().getId());
        record(HealthStatus.UP); assertEquals(AlertStatus.RESOLVED, alerts.findById(id).orElseThrow().getStatus());
    }

    @Test void oppositeResultsInterruptPendingFailureAndRecoverySequences() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.UP); record(HealthStatus.DOWN);
        assertEquals(1, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); UUID id = active().getId();
        record(HealthStatus.UP); record(HealthStatus.DOWN); record(HealthStatus.UP);
        assertEquals(1, state().getConsecutiveRecoveries()); assertEquals(id, active().getId());
        record(HealthStatus.UP); assertEquals(AlertStatus.RESOLVED, alerts.findById(id).orElseThrow().getStatus());
    }

    @Test void configuredThresholdsAndSeverityAreUsed() {
        configure(true, 1, 3, AlertSeverity.CRITICAL);
        record(HealthStatus.DOWN); var alert = active(); assertEquals(AlertSeverity.CRITICAL, alert.getSeverity());
        assertEquals(1, alert.getFailureThreshold()); assertEquals(3, alert.getRecoveryThreshold());
        record(HealthStatus.UP); record(HealthStatus.UP); assertEquals(AlertStatus.ACTIVE, active().getStatus());
        record(HealthStatus.UP); assertEquals(AlertStatus.RESOLVED, alerts.findById(alert.getId()).orElseThrow().getStatus());
    }

    @Test void disabledRulesStillPersistHealthButDoNotCountAndReenableStartsFresh() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        configure(false, 3, 2, AlertSeverity.HIGH);
        assertEquals(0, state().getConsecutiveFailures());
        outage(); assertEquals(5, results.count()); assertEquals(0, alerts.count());
        configure(true, 3, 2, AlertSeverity.HIGH);
        record(HealthStatus.DOWN); assertEquals(1, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); assertEquals(1, alerts.count());
    }

    @Test void serviceToggleResetsWithoutObservationsAndOldInFlightResultCannotCount() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var oldTarget = store.loadTarget(first.getId()).orElseThrow(); Instant inFlight = tick();
        tick(); serviceActions.disable("evaluator-owner", alpha.getId(), first.getId());
        store.record(oldTarget, observation(HealthStatus.DOWN, tick())); assertEquals(2, results.count());
        tick(); serviceActions.enable("evaluator-owner", alpha.getId(), first.getId());
        store.record(oldTarget, observation(HealthStatus.DOWN, inFlight));
        assertEquals(0, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        outage(); assertEquals(1, alerts.count());
    }

    @Test void disabledServiceAlsoBlocksDirectEvaluationOfAlreadyPersistedEvidence() {
        var pending = persist(first, HealthStatus.DOWN, tick());
        tick(); serviceActions.disable("evaluator-owner", alpha.getId(), first.getId());
        evaluate(pending);
        assertEquals(0, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        assertEquals(1, results.count());
    }

    @ParameterizedTest @EnumSource(value = ServiceType.class, names = "HTTP", mode = EnumSource.Mode.EXCLUDE)
    void nonHttpOrRemovedServicesAreNotEvaluated(ServiceType type) {
        first.changeServiceType(type); services.save(first);
        var pending = persist(first, HealthStatus.DOWN, tick()); evaluate(pending);
        assertEquals(0, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        UUID serviceId = first.getId(), resultId = pending.getId(); services.deleteById(serviceId);
        engine.evaluate(alpha.getId(), serviceId, resultId);
        assertEquals(0, states.count()); assertEquals(0, alerts.count());
    }

    @Test void thresholdChangesDiscardAccumulatedCountersButIdenticalPutDoesNot() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        configure(true, 3, 2, AlertSeverity.HIGH); // Persisting the same effective defaults is not a change.
        assertEquals(2, state().getConsecutiveFailures());
        configure(true, 1, 2, AlertSeverity.HIGH);
        assertEquals(0, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        record(HealthStatus.DOWN); assertEquals(1, active().getOccurrenceCount());
    }

    @Test void configChangesKeepActiveAlertIdentitySeverityAndHistoryAndResetRecoveries() {
        outage(); UUID id = active().getId(); record(HealthStatus.UP);
        configure(false, 1, 1, AlertSeverity.CRITICAL);
        record(HealthStatus.UP); assertEquals(id, active().getId());
        configure(true, 1, 3, AlertSeverity.CRITICAL);
        record(HealthStatus.DOWN); assertEquals(id, active().getId()); assertEquals(AlertSeverity.HIGH, active().getSeverity());
        assertEquals(4, active().getOccurrenceCount());
        record(HealthStatus.UP); record(HealthStatus.UP); assertEquals(id, active().getId()); record(HealthStatus.UP);
        var resolved = alerts.findById(id).orElseThrow(); assertEquals(3, resolved.getResolvedAfterRecoveries());
        record(HealthStatus.DOWN); assertNotEquals(id, active().getId()); assertEquals(AlertSeverity.CRITICAL, active().getSeverity());
        assertEquals(2, alerts.count());
    }

    @Test void fallbackDetectsConfigurationEditsAndDoesNotRetroactivelyCountTheirCurrentObservation() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var rule = AlertRule.createDefault(alpha.getId(), first.getId(), tick());
        rule.changeConfiguration(true, 1, 2, AlertSeverity.HIGH, current.get()); rules.save(rule);
        record(HealthStatus.DOWN); assertEquals(0, state().getConsecutiveFailures()); assertEquals(0, alerts.count());
        record(HealthStatus.DOWN); assertEquals(1, alerts.count());
    }

    @Test void duplicateOlderAndEqualStartedEvidenceCannotRegressOrResolve() {
        outage(); UUID id = active().getId(); var last = state();
        var trigger = results.findById(last.getLastProcessedHealthCheckResultId()).orElseThrow();
        evaluate(trigger); evaluate(trigger);
        evaluate(persist(first, HealthStatus.UP, trigger.getStartedAt().minusSeconds(1)));
        evaluate(persist(first, HealthStatus.UP, trigger.getStartedAt()));
        assertEquals(3, active().getOccurrenceCount()); assertEquals(id, active().getId());
        assertEquals(last.getLastProcessedHealthCheckResultId(), state().getLastProcessedHealthCheckResultId());
        var recovery = record(HealthStatus.UP); evaluate(recovery);
        assertEquals(1, state().getConsecutiveRecoveries()); assertEquals(id, active().getId());
        record(HealthStatus.UP); assertEquals(AlertStatus.RESOLVED, alerts.findById(id).orElseThrow().getStatus());
        evaluate(trigger); assertEquals(1, alerts.count());
    }

    @Test void newEngineInstanceContinuesPersistedStreakAfterEntityManagerAndTransactionBoundaries() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var restarted = new AlertEvaluationEngine(services, results, rules, states, alerts, clock, incidentCorrelation);
        var third = persist(first, HealthStatus.DOWN, tick());
        new TransactionTemplate(transactions).executeWithoutResult(status -> restarted.evaluate(alpha.getId(), first.getId(), third.getId()));
        assertEquals(3, state().getConsecutiveFailures()); assertEquals(1, alerts.count());
    }

    @Test void concurrentDifferentAndDuplicateResultsCannotCreateMultipleActiveAlerts() throws Exception {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var third = persist(first, HealthStatus.DOWN, tick()); var fourth = persist(first, HealthStatus.DOWN, tick());
        ExecutorService workers = Executors.newFixedThreadPool(5);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), ready = new CountDownLatch(4), start = new CountDownLatch(1);
        try {
            Future<?> holder = workers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                services.findByIdAndOrganisationIdForUpdate(first.getId(), alpha.getId()).orElseThrow(); held.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture lock timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var pending = new java.util.ArrayList<Future<?>>();
            for (var result : List.of(third, fourth, third, fourth)) pending.add(workers.submit(() -> {
                ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture start timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                evaluate(result);
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); int waiting = 0;
            while (System.nanoTime() < until && waiting < 2) {
                waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%monitored_services%'", Integer.class);
                if (waiting < 2) Thread.sleep(20);
            }
            assertTrue(waiting >= 2); release.countDown(); holder.get(10, TimeUnit.SECONDS);
            for (var future : pending) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, alerts.count()); assertEquals(1, states.count());
            assertTrue(active().getOccurrenceCount() == 3 || active().getOccurrenceCount() == 4); // Older third is skipped if fourth acquires lock first.
            assertEquals(fourth.getId(), state().getLastProcessedHealthCheckResultId());
        } finally { release.countDown(); start.countDown(); workers.shutdownNow(); }
    }

    @Test void acceptedResultStateAndAlertRollbackTogetherWhenPersistenceFails() {
        configure(true, 1, 2, AlertSeverity.HIGH);
        jdbc.execute("ALTER TABLE alerts ADD CONSTRAINT fixture_reject_alerts CHECK (false)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> record(HealthStatus.DOWN));
            assertEquals(0, results.count()); assertEquals(0, alerts.count());
            assertEquals(0, state().getConsecutiveFailures()); assertNull(state().getLastProcessedHealthCheckResultId());
        } finally { jdbc.execute("ALTER TABLE alerts DROP CONSTRAINT fixture_reject_alerts"); }
    }

    @Test void changedOrRemovedTargetsAreDiscardedBeforeEvaluation() {
        var target = store.loadTarget(first.getId()).orElseThrow();
        first.changeBaseUrl("http://changed.example.test"); services.save(first);
        store.record(target, observation(HealthStatus.DOWN, tick()));
        assertEquals(0, results.count()); assertEquals(0, states.count()); assertEquals(0, alerts.count());
        services.deleteById(first.getId()); store.record(target, observation(HealthStatus.DOWN, tick()));
        assertEquals(0, states.count());
    }

    @Test void servicesAndEvidenceCannotCrossTenantsOrServices() {
        var foreign = persist(other, HealthStatus.DOWN, tick());
        engine.evaluate(alpha.getId(), first.getId(), foreign.getId());
        engine.evaluate(alpha.getId(), other.getId(), foreign.getId());
        engine.evaluate(alpha.getId(), first.getId(), UUID.randomUUID());
        assertEquals(0, states.count()); assertEquals(0, alerts.count());
        outage(); assertTrue(alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(beta.getId(), first.getId()).isEmpty());
        evaluate(foreign); assertEquals(1, states.findByOrganisationIdAndMonitoredServiceId(beta.getId(), other.getId()).orElseThrow().getConsecutiveFailures());
        assertEquals(0, states.findByOrganisationIdAndMonitoredServiceId(alpha.getId(), other.getId()).stream().count());
        assertEquals(1, alerts.count());
    }

    @Test void databaseRejectsDuplicateActiveAlertInvalidScopeAndForeignEvidence() {
        outage(); UUID id = active().getId();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO alerts SELECT gen_random_uuid(), organisation_id, monitored_service_id, category, severity, status,
                first_seen_at,last_seen_at,resolved_at,occurrence_count,failure_threshold,recovery_threshold,resolved_after_recoveries,
                first_failure_health_check_result_id,triggering_health_check_result_id,last_failure_health_check_result_id,
                resolution_health_check_result_id,triggering_http_status_code,triggering_response_time_ms,triggering_failure_detail
                FROM alerts WHERE id=?
                """, id));
        var foreign = persist(other, HealthStatus.DOWN, tick());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alerts SET triggering_health_check_result_id=? WHERE id=?", foreign.getId(), id));
        var sibling = create(alpha); var siblingResult = persist(sibling, HealthStatus.DOWN, tick());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alerts SET last_failure_health_check_result_id=? WHERE id=?", siblingResult.getId(), id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alert_evaluation_states SET organisation_id=? WHERE monitored_service_id=?", beta.getId(), first.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alert_evaluation_states SET last_processed_health_check_result_id=? WHERE monitored_service_id=?", foreign.getId(), first.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alerts SET status='RESOLVED' WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alert_evaluation_states SET consecutive_failures=-1 WHERE monitored_service_id=?", first.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alert_evaluation_states SET consecutive_recoveries=1 WHERE monitored_service_id=?", first.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO alert_evaluation_states SELECT gen_random_uuid(),organisation_id,monitored_service_id,consecutive_failures,consecutive_recoveries,first_failure_health_check_result_id,first_failure_at,last_processed_health_check_result_id,last_processed_started_at,configuration_key,configuration_changed_at,updated_at FROM alert_evaluation_states WHERE monitored_service_id=?", first.getId()));
        assertEquals(1, alerts.count()); assertEquals(AlertStatus.ACTIVE, active().getStatus());
    }

    @Test void deletingServiceCascadesOwnedEvaluationDataButEvidenceCannotBeDeletedAlone() {
        outage();
        assertThrows(DataIntegrityViolationException.class, () -> results.deleteById(active().getTriggeringHealthCheckResultId()));
        services.deleteById(first.getId());
        assertEquals(0, alerts.count()); assertEquals(0, states.count()); assertEquals(0, results.count());
    }
}
