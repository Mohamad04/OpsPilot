package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
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
class IncidentManagementIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant BASE = Instant.parse("2026-10-27T12:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired IncidentService application;
    @Autowired IncidentRepository incidents;
    @Autowired IncidentEventRepository events;
    @Autowired IncidentAlertRepository links;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired UserRepository users;
    @Autowired MonitoredServiceRepository services;
    @Autowired AlertRepository alerts;
    @Autowired HealthCheckResultRepository results;
    @Autowired HealthCheckStore store;
    @Autowired IncidentCorrelationService correlation;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(BASE);
    private Organisation alpha, beta;
    private String owner, outsider;
    private UUID ownerId;
    private MonitoredService service, foreignService;

    @BeforeEach void fixtures() {
        // Fresh tenants retain immutable events across committed concurrency/HTTP tests.
        alpha = organisations.save(Organisation.create("Incident API Alpha", "incident-api-a-" + UUID.randomUUID()));
        beta = organisations.save(Organisation.create("Incident API Beta", "incident-api-b-" + UUID.randomUUID()));
        owner = "owner-" + UUID.randomUUID(); outsider = "outsider-" + UUID.randomUUID();
        ownerId = member(owner, OrganisationRole.OWNER).getUser().getId();
        users.save(User.create(outsider, "Outsider", outsider + "@example.test"));
        service = services.save(MonitoredService.create(alpha, "API fixture", ServiceType.HTTP, Environment.TEST,
                "https://fixture.example.test", "/health", "Incident test"));
        foreignService = services.save(MonitoredService.create(beta, "Other tenant", ServiceType.HTTP, Environment.TEST,
                "https://other.example.test", "/health", "Other test"));
        now.set(BASE); when(clock.instant()).thenAnswer(call -> now.get());
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Invalid fixture JWT"));
    }

    private OrganisationMembership member(String subject, OrganisationRole role) {
        var user = users.save(User.create(subject, "Member", subject + "@example.test"));
        return memberships.save(OrganisationMembership.create(alpha, user, role));
    }

    private Incident open(Organisation organisation, AlertSeverity severity) {
        return incidents.save(Incident.open(organisation.getId(), "API incident", "Safe incident description", severity, BASE.minusSeconds(1), null));
    }

    private String base() { return "/api/organisations/" + alpha.getId() + "/incidents"; }

    private ResultActions patchStatus(UUID id, IncidentStatus target, long version, String subject) throws Exception {
        return mvc.perform(patch(base() + "/" + id + "/status").with(jwt().jwt(token -> token.subject(subject)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\",\"expectedVersion\":" + version + "}"));
    }

    private com.opspilot.backend.web.dto.IncidentResponse transition(UUID id, IncidentStatus target) {
        long version = application.details(owner, alpha.getId(), id).version();
        return application.changeStatus(owner, alpha.getId(), id, target, version);
    }

    private Incident reach(IncidentStatus status) {
        var incident = open(alpha, AlertSeverity.HIGH);
        if (status != IncidentStatus.OPEN) transition(incident.getId(), IncidentStatus.ACKNOWLEDGED);
        if (status == IncidentStatus.INVESTIGATING || status == IncidentStatus.MITIGATED) transition(incident.getId(), IncidentStatus.INVESTIGATING);
        if (status == IncidentStatus.MITIGATED) transition(incident.getId(), IncidentStatus.MITIGATED);
        if (status == IncidentStatus.RESOLVED || status == IncidentStatus.CLOSED) transition(incident.getId(), IncidentStatus.RESOLVED);
        if (status == IncidentStatus.CLOSED) transition(incident.getId(), IncidentStatus.CLOSED);
        return incidents.findByIdAndOrganisationId(incident.getId(), alpha.getId()).orElseThrow();
    }

    private long countEvents(UUID id) { return jdbc.queryForObject("select count(*) from incident_events where organisation_id=? and incident_id=?", Long.class, alpha.getId(), id); }

    @ParameterizedTest @CsvSource({"OPEN,ACKNOWLEDGED", "ACKNOWLEDGED,INVESTIGATING", "ACKNOWLEDGED,RESOLVED",
            "INVESTIGATING,MITIGATED", "INVESTIGATING,RESOLVED", "MITIGATED,INVESTIGATING", "MITIGATED,RESOLVED",
            "RESOLVED,CLOSED", "RESOLVED,INVESTIGATING", "CLOSED,INVESTIGATING"})
    void everyAllowedTransitionWritesAtomicHumanAuditMetadata(IncidentStatus from, IncidentStatus to) throws Exception {
        var incident = reach(from); long before = countEvents(incident.getId());
        var response = patchStatus(incident.getId(), to, incident.getVersion(), owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(to.name())).andExpect(jsonPath("$.version").value(incident.getVersion() + 1))
                .andReturn().getResponse().getContentAsString();
        assertEquals(before + 1, countEvents(incident.getId()));
        var event = events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow();
        assertEquals(ownerId, event.getActorUserId()); assertEquals(IncidentEventType.STATUS_CHANGED, event.getEventType());
        assertEquals(from, event.getPreviousStatus()); assertEquals(to, event.getNewStatus()); assertNull(event.getSourceAlertId());
        if (!from.isOngoing() && to.isOngoing()) assertTrue(json.readTree(response).get("resolvedAt").isNull());
    }

    @Test void invalidRepeatedAndStaleRequestsHaveNoAuditSideEffects() throws Exception {
        var incident = open(alpha, AlertSeverity.HIGH);
        patchStatus(incident.getId(), IncidentStatus.OPEN, 0, owner).andExpect(status().isConflict());
        patchStatus(incident.getId(), IncidentStatus.CLOSED, 0, owner).andExpect(status().isConflict());
        patchStatus(incident.getId(), IncidentStatus.ACKNOWLEDGED, 5, owner).andExpect(status().isConflict());
        assertEquals(0, countEvents(incident.getId()));
        patchStatus(incident.getId(), IncidentStatus.ACKNOWLEDGED, 0, owner).andExpect(status().isOk());
        patchStatus(incident.getId(), IncidentStatus.ACKNOWLEDGED, 1, owner).andExpect(status().isConflict());
        assertEquals(1, countEvents(incident.getId()));
    }

    @ParameterizedTest @EnumSource(OrganisationRole.class)
    void readsAreAllowedForAllExistingRolesAndWritesAreRestricted(OrganisationRole role) throws Exception {
        String subject = "role-" + UUID.randomUUID(); member(subject, role); var incident = open(alpha, AlertSeverity.LOW);
        for (String suffix : List.of("", "/" + incident.getId(), "/" + incident.getId() + "/events")) {
            mvc.perform(get(base() + suffix).with(jwt().jwt(token -> token.subject(subject)))).andExpect(status().isOk());
        }
        patchStatus(incident.getId(), IncidentStatus.ACKNOWLEDGED, 0, subject).andExpect(status().is(role == OrganisationRole.VIEWER ? 403 : 200));
        assertEquals(role == OrganisationRole.VIEWER ? 0 : 1, countEvents(incident.getId()));
    }

    @Test void foreignResourcesAndNonMembersCannotDiscoverOrChangeIncidents() throws Exception {
        var foreign = open(beta, AlertSeverity.CRITICAL); var local = open(alpha, AlertSeverity.LOW);
        String missing = mvc.perform(get(base() + "/" + UUID.randomUUID()).with(jwt().jwt(t -> t.subject(owner))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(missing, mvc.perform(get(base() + "/" + foreign.getId()).with(jwt().jwt(t -> t.subject(owner))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString());
        mvc.perform(get(base() + "/" + foreign.getId() + "/events").with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isNotFound());
        patchStatus(foreign.getId(), IncidentStatus.ACKNOWLEDGED, 0, owner).andExpect(status().isNotFound());
        for (String suffix : List.of("", "/" + local.getId(), "/" + local.getId() + "/events")) {
            mvc.perform(get(base() + suffix).with(jwt().jwt(t -> t.subject(outsider)))).andExpect(status().isNotFound());
        }
        patchStatus(local.getId(), IncidentStatus.ACKNOWLEDGED, 0, outsider).andExpect(status().isNotFound());
        assertEquals(0, countEvents(local.getId()));
    }

    @Test void actorComesFromTheJwtAndMembershipRevocationIsEnforced() throws Exception {
        var incident = open(alpha, AlertSeverity.LOW);
        mvc.perform(patch(base() + "/" + incident.getId() + "/status").with(jwt().jwt(t -> t.subject(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":0,\"actorUserId\":\"" + UUID.randomUUID()
                        + "\",\"organisationId\":\"" + beta.getId() + "\",\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());
        assertEquals(ownerId, events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow().getActorUserId());
        memberships.delete(memberships.findByOrganisationIdAndUserId(alpha.getId(), ownerId).orElseThrow());
        mvc.perform(get(base() + "/" + incident.getId()).with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isNotFound());
        patchStatus(incident.getId(), IncidentStatus.INVESTIGATING, 1, owner).andExpect(status().isNotFound());
        assertEquals(1, countEvents(incident.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"", "/missing", "/events", "/status"})
    void missingAndInvalidBearerTokensAreRejected(String endpoint) throws Exception {
        var incident = open(alpha, AlertSeverity.LOW);
        String path = endpoint.isEmpty() ? base() : base() + "/" + incident.getId() + (endpoint.equals("/missing") ? "" : endpoint);
        for (String bearer : new String[]{null, "Bearer invalid.jwt.token"}) {
            var request = endpoint.equals("/status") ? patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":0}") : get(path);
            if (bearer != null) request.header("Authorization", bearer);
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        assertEquals(0, countEvents(incident.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"status\":null,\"expectedVersion\":0}",
            "{\"status\":\"ACKNOWLEDGED\"}", "{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":-1}",
            "{\"status\":\"INVALID\",\"expectedVersion\":0}", "{malformed",
            "{\"status\":1,\"expectedVersion\":0}", "{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":0.5}",
            "{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":\"0\"}", "{\"status\":\"ACKNOWLEDGED\",\"expectedVersion\":9223372036854775808}"})
    void invalidBodiesAre400WithNoWrites(String body) throws Exception {
        var incident = open(alpha, AlertSeverity.HIGH);
        mvc.perform(patch(base() + "/" + incident.getId() + "/status").with(jwt().jwt(t -> t.subject(owner)))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertEquals(0, countEvents(incident.getId()));
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=2147483647&size=100", "status=INVALID", "severity=INVALID", "serviceId=invalid", "sort=title,desc"})
    void invalidFiltersAndPagingAre400(String query) throws Exception {
        mvc.perform(get(base() + "?" + query).with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/not-a-uuid").with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isBadRequest());
    }

    @Test void databaseFilteringPaginationSortingAndTimelineReadsDoNotModifyData() throws Exception {
        mvc.perform(get(base()).with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        var low = open(alpha, AlertSeverity.LOW); var high = reach(IncidentStatus.ACKNOWLEDGED); open(beta, AlertSeverity.CRITICAL);
        mvc.perform(get(base() + "?status=ACKNOWLEDGED&severity=HIGH").with(jwt().jwt(t -> t.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(high.getId().toString()));
        for (String sort : List.of("createdAt,asc", "createdAt,desc")) {
            var first = json.readTree(mvc.perform(get(base() + "?size=1&sort=" + sort).with(jwt().jwt(t -> t.subject(owner))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andReturn().getResponse().getContentAsString());
            String expected = List.of(low.getId().toString(), high.getId().toString()).stream().sorted(sort.endsWith("asc") ? java.util.Comparator.naturalOrder() : java.util.Comparator.reverseOrder()).findFirst().orElseThrow();
            assertEquals(expected, first.get("content").get(0).get("id").asText());
        }
        mvc.perform(get(base() + "?page=50&size=1").with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get(base() + "?serviceId=" + foreignService.getId()).with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/" + high.getId() + "/events?size=1").with(jwt().jwt(t -> t.subject(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].actorUserId").value(ownerId.toString()));
        assertEquals(1, countEvents(high.getId())); assertEquals(0, countEvents(low.getId()));
        assertEquals(high.getVersion(), incidents.findByIdAndOrganisationId(high.getId(), alpha.getId()).orElseThrow().getVersion());
    }

    private void record(HealthStatus status) {
        Instant at = now.updateAndGet(value -> value.plusSeconds(1));
        store.record(store.loadTarget(service.getId()).orElseThrow(), new HealthObservation(at, at.plusMillis(1), status,
                status == HealthStatus.DOWN ? 503 : 200, 1, null));
    }

    private Incident automaticIncident() {
        record(HealthStatus.DOWN); record(HealthStatus.DOWN); record(HealthStatus.DOWN);
        var alert = alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(alpha.getId(), service.getId()).getFirst();
        var link = links.findFirstByOrganisationIdAndAlertIdOrderByIdAsc(alpha.getId(), alert.getId()).orElseThrow();
        return incidents.findByIdAndOrganisationId(link.getIncidentId(), alpha.getId()).orElseThrow();
    }

    @Test void automaticRecoveryAndManualLifecycleRemainCompatibleAndReopeningConflictsAre409() throws Exception {
        var incident = automaticIncident(); transition(incident.getId(), IncidentStatus.ACKNOWLEDGED);
        patchStatus(incident.getId(), IncidentStatus.RESOLVED, 1, owner).andExpect(status().isConflict());
        assertEquals(3, countEvents(incident.getId())); // creation, attachment, acknowledgement
        record(HealthStatus.UP); record(HealthStatus.UP);
        assertEquals(IncidentStatus.ACKNOWLEDGED, application.details(owner, alpha.getId(), incident.getId()).status());
        assertEquals(IncidentEventType.ALERT_RESOLVED, events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow().getEventType());
        var resolved = transition(incident.getId(), IncidentStatus.RESOLVED);
        mvc.perform(get(base() + "?serviceId=" + service.getId()).with(jwt().jwt(t -> t.subject(owner)))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        var next = automaticIncident(); assertNotEquals(incident.getId(), next.getId());
        patchStatus(incident.getId(), IncidentStatus.INVESTIGATING, resolved.version(), owner).andExpect(status().isConflict());
        assertEquals(IncidentStatus.RESOLVED, application.details(owner, alpha.getId(), incident.getId()).status());
    }

    @Test void clockRegressionDoesNotRegressTimelineAndReopeningKeepsAcknowledgement() {
        var incident = reach(IncidentStatus.CLOSED);
        var previous = events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow();
        now.set(BASE.minusSeconds(100));
        var reopened = transition(incident.getId(), IncidentStatus.INVESTIGATING);
        var latest = events.findFirstByOrganisationIdAndIncidentIdOrderByOccurredAtDescIdDesc(alpha.getId(), incident.getId()).orElseThrow();
        assertTrue(latest.getOccurredAt().isAfter(previous.getOccurredAt()));
        assertEquals(incident.getAcknowledgedAt(), reopened.acknowledgedAt()); assertNull(reopened.resolvedAt());
    }

    @Test void timelineFailureRollsBackStatusVersionAndTimestamp() {
        var incident = open(alpha, AlertSeverity.HIGH);
        jdbc.execute("alter table incident_events add constraint fixture_reject_status check (organisation_id <> '" + alpha.getId() + "'::uuid)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> transition(incident.getId(), IncidentStatus.ACKNOWLEDGED));
            var unchanged = application.details(owner, alpha.getId(), incident.getId());
            assertEquals(IncidentStatus.OPEN, unchanged.status()); assertEquals(0, unchanged.version()); assertNull(unchanged.acknowledgedAt());
            assertEquals(0, countEvents(incident.getId()));
        } finally { jdbc.execute("alter table incident_events drop constraint fixture_reject_status"); }
        assertEquals(IncidentStatus.ACKNOWLEDGED, transition(incident.getId(), IncidentStatus.ACKNOWLEDGED).status());
    }

    @Test void concurrentRequestsWithTheSameVersionProduceOneSuccessAndOne409() throws Exception {
        var incident = reach(IncidentStatus.ACKNOWLEDGED); long before = countEvents(incident.getId());
        var pool = Executors.newFixedThreadPool(3); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                incidents.findByIdAndOrganisationIdForUpdate(incident.getId(), alpha.getId()).orElseThrow(); held.countDown(); await(release);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var pending = new java.util.ArrayList<Future<Integer>>();
            for (var target : List.of(IncidentStatus.INVESTIGATING, IncidentStatus.RESOLVED)) pending.add(pool.submit(() -> {
                ready.countDown(); await(start); return patchStatus(incident.getId(), target, incident.getVersion(), owner).andReturn().getResponse().getStatus();
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); int waiting = 0;
            while (waiting < 2 && System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%incidents%'", Integer.class);
                if (waiting < 2) Thread.sleep(20);
            }
            assertTrue(waiting >= 2); release.countDown(); holder.get(10, TimeUnit.SECONDS);
            var codes = new java.util.ArrayList<Integer>(); for (var future : pending) codes.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(200, 409), codes.stream().sorted().toList());
        } finally { release.countDown(); start.countDown(); pool.shutdownNow(); }
        assertEquals(before + 1, countEvents(incident.getId()));
        assertEquals(incident.getVersion() + 1, application.details(owner, alpha.getId(), incident.getId()).version());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture latch timeout"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    @Test void humanCommentsDoNotSuppressAutomaticRecoveryEvidenceOrChangeAlertCorrelation() {
        var incident = automaticIncident();
        var comment = application.addComment(owner, alpha.getId(), incident.getId(), "Investigating recovery", UUID.randomUUID().toString());
        assertEquals(ownerId, comment.actorUserId()); assertNull(comment.sourceAlertId());
        record(HealthStatus.UP); record(HealthStatus.UP);
        var alert = alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(alpha.getId(), service.getId()).getFirst();
        assertEquals(AlertStatus.RESOLVED, alert.getStatus());
        var recovery = events.findByOrganisationIdAndSourceAlertIdAndEventType(alpha.getId(), alert.getId(), IncidentEventType.ALERT_RESOLVED).orElseThrow();
        assertNull(recovery.getActorUserId()); assertEquals(incident.getId(), recovery.getIncidentId());
        assertEquals(IncidentStatus.OPEN, application.details(owner, alpha.getId(), incident.getId()).status());
        correlation.process(alpha.getId(), service.getId(), alert.getId()); assertEquals(4, countEvents(incident.getId()));
    }

    @Test void legacyRecoveryNoteIsPreservedAndNotDuplicatedAsDedicatedRecoveryEvent() {
        var incident = automaticIncident();
        var alert = alerts.findByOrganisationIdAndMonitoredServiceIdOrderByFirstSeenAtDesc(alpha.getId(), service.getId()).getFirst();
        Instant at = now.updateAndGet(value -> value.plusSeconds(1));
        var up = results.save(new HealthCheckResult(alpha.getId(), service.getId(), at, at, HealthStatus.UP, 200, 1, null));
        alert.resolve(up, 2); alerts.save(alert);
        var legacy = events.save(IncidentEvent.forSystemAlert(incident, alert, IncidentEventType.COMMENT_ADDED, at, "Original legacy recovery note"));
        correlation.process(alpha.getId(), service.getId(), alert.getId());
        assertEquals(3, countEvents(incident.getId()));
        assertEquals("Original legacy recovery note", events.findByIdAndOrganisationId(legacy.getId(), alpha.getId()).orElseThrow().getDetails());
        assertTrue(events.findByOrganisationIdAndSourceAlertIdAndEventType(alpha.getId(), alert.getId(), IncidentEventType.ALERT_RESOLVED).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> events.save(IncidentEvent.forSystemAlert(incident, alert,
                IncidentEventType.ALERT_RESOLVED, at, "Duplicate new recovery")));
    }
}
