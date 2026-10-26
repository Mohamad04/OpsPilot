package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Real application services/repositories, Flyway V1–V7 and disposable PostgreSQL; no business database.
@SpringBootTest(properties = "opspilot.monitoring.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class AlertRuleIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00.123456Z");
    private static final String DEFAULTS = """
            {"enabled":true,"consecutiveFailures":3,"consecutiveRecoveries":2,"severity":"HIGH"}
            """;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AlertRuleService application;
    @Autowired AlertRuleRepository rules;
    @Autowired MonitoredServiceRepository services;
    @Autowired OrganisationRepository organisations;
    @Autowired UserRepository users;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired HealthCheckResultRepository results;
    @Autowired HealthCheckStore healthStore;
    @Autowired MonitoredServicesService serviceActions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean Clock clock;
    private Organisation alpha, beta;
    private MonitoredService first, second;
    private User owner;
    private OrganisationMembership ownerMembership;

    @BeforeEach void fixtures() {
        rules.deleteAll(); results.deleteAll(); services.deleteAll(); memberships.deleteAll(); organisations.deleteAll(); users.deleteAll();
        when(clock.instant()).thenReturn(NOW);
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Invalid fixture JWT"));
        alpha = organisations.save(Organisation.create("Alpha alerts", "alpha-alerts"));
        beta = organisations.save(Organisation.create("Beta alerts", "beta-alerts"));
        owner = users.save(User.create("alert-owner", "Owner", "alert-owner@example.test"));
        var viewer = users.save(User.create("alert-viewer", "Viewer", "alert-viewer@example.test"));
        var betaOwner = users.save(User.create("beta-owner", "Beta owner", "beta-owner@example.test"));
        users.save(User.create("alert-outsider", "Outsider", "alert-outsider@example.test"));
        ownerMembership = memberships.save(OrganisationMembership.create(alpha, owner, OrganisationRole.OWNER));
        memberships.save(OrganisationMembership.create(alpha, viewer, OrganisationRole.VIEWER));
        memberships.save(OrganisationMembership.create(beta, betaOwner, OrganisationRole.OWNER));
        first = createService(alpha, ServiceType.HTTP);
        second = createService(beta, ServiceType.HTTP);
    }

    private MonitoredService createService(Organisation organisation, ServiceType type) {
        return services.save(MonitoredService.create(organisation, "Alert fixture", type, Environment.TEST,
                "https://fixture.example.test", "/health", "Test team"));
    }
    private String path(UUID organisation, UUID service) {
        return "/api/organisations/" + organisation + "/monitored-services/" + service + "/alert-rule";
    }
    private String ownPath() { return path(alpha.getId(), first.getId()); }
    private JsonNode putRule(String body) throws Exception {
        return json.readTree(mvc.perform(put(ownPath()).with(jwt().jwt(token -> token.subject("alert-owner")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void getReturnsDefaultsForViewerWithoutCreatingRows() throws Exception {
        for (int i = 0; i < 2; i++) mvc.perform(get(ownPath()).with(jwt().jwt(token -> token.subject("alert-viewer"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isEmpty())
                .andExpect(jsonPath("$.organisationId").value(alpha.getId().toString()))
                .andExpect(jsonPath("$.monitoredServiceId").value(first.getId().toString()))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.consecutiveFailures").value(3))
                .andExpect(jsonPath("$.consecutiveRecoveries").value(2)).andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.createdAt").isEmpty()).andExpect(jsonPath("$.updatedAt").isEmpty());
        assertEquals(0, rules.count());
        assertEquals(0, results.count());
    }

    @Test void putCreatesThenUpdatesTheSameRowAndRepeatedPayloadIsIdempotent() throws Exception {
        JsonNode created = putRule(DEFAULTS);
        UUID id = UUID.fromString(created.get("id").asText());
        assertEquals(NOW.toString(), created.get("createdAt").asText());
        assertEquals(NOW.toString(), created.get("updatedAt").asText());
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        assertEquals(created, putRule(DEFAULTS));
        JsonNode updated = putRule("""
                {"enabled":false,"consecutiveFailures":20,"consecutiveRecoveries":1,"severity":"CRITICAL"}
                """);
        assertEquals(id.toString(), updated.get("id").asText());
        assertEquals(created.get("createdAt"), updated.get("createdAt"));
        assertEquals(NOW.plusSeconds(10).toString(), updated.get("updatedAt").asText());
        when(clock.instant()).thenReturn(NOW.plusSeconds(20));
        assertEquals(updated, putRule("""
                {"enabled":false,"consecutiveFailures":20,"consecutiveRecoveries":1,"severity":"CRITICAL"}
                """));
        assertEquals(1, rules.count());
        var saved = rules.findById(id).orElseThrow();
        assertFalse(saved.isEnabled()); assertEquals(20, saved.getConsecutiveFailures());
        assertEquals(1, saved.getConsecutiveRecoveries()); assertEquals(AlertSeverity.CRITICAL, saved.getSeverity());
        mvc.perform(get(ownPath()).with(jwt().jwt(token -> token.subject("alert-viewer"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @ParameterizedTest @EnumSource(value = OrganisationRole.class, names = {"OWNER", "ADMIN", "ENGINEER"})
    void writersCanCreateAndUpdate(OrganisationRole role) throws Exception {
        ownerMembership.changeRole(role); memberships.save(ownerMembership);
        putRule(DEFAULTS);
        putRule("""
                {"enabled":false,"consecutiveFailures":1,"consecutiveRecoveries":20,"severity":"LOW"}
                """);
        assertEquals(1, rules.count());
        assertFalse(rules.findAll().getFirst().isEnabled());
    }

    @Test void viewerCannotCreateOrModifyEvenWhenJwtClaimsOwner() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(put(ownPath()).with(jwt().jwt(token -> token.subject("alert-viewer")
                            .claim("realm_access", java.util.Map.of("roles", List.of("OWNER")))))
                            .contentType(MediaType.APPLICATION_JSON).content(DEFAULTS))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.errors").isEmpty());
            if (i == 0) putRule(DEFAULTS);
        }
        assertEquals(1, rules.count());
        assertEquals(AlertSeverity.HIGH, rules.findAll().getFirst().getSeverity());
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void nonMembersCannotDistinguishExistingAndMissingTenants(String method) throws Exception {
        var responses = new java.util.ArrayList<String>();
        for (UUID organisation : List.of(beta.getId(), UUID.randomUUID())) {
            var request = method.equals("GET") ? get(path(organisation, second.getId()))
                    : put(path(organisation, second.getId())).contentType(MediaType.APPLICATION_JSON).content(DEFAULTS);
            responses.add(mvc.perform(request.with(jwt().jwt(token -> token.subject("alert-outsider"))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Organisation membership not found"))
                    .andReturn().getResponse().getContentAsString());
        }
        assertEquals(responses.getFirst(), responses.getLast());
        assertEquals(0, rules.count());
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void foreignAndMissingServicesReturn404EvenForMemberOfBothTenants(String method) throws Exception {
        memberships.save(OrganisationMembership.create(beta, owner, OrganisationRole.ENGINEER));
        for (UUID service : List.of(second.getId(), UUID.randomUUID())) {
            var request = method.equals("GET") ? get(path(alpha.getId(), service))
                    : put(path(alpha.getId(), service)).contentType(MediaType.APPLICATION_JSON).content(DEFAULTS);
            mvc.perform(request.with(jwt().jwt(token -> token.subject("alert-owner"))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        }
        assertEquals(0, rules.count());
    }

    @Test void storedRulesCannotBeReadThroughAnotherTenantScopeOrFrontendIdentityFields() throws Exception {
        String spoofed = DEFAULTS.strip().replace("}", ",\"organisationId\":\"" + beta.getId()
                + "\",\"monitoredServiceId\":\"" + second.getId() + "\",\"role\":\"OWNER\",\"subject\":\"beta-owner\"}");
        putRule(spoofed);
        assertEquals(1, rules.count());
        var saved = rules.findAll().getFirst();
        assertEquals(alpha.getId(), saved.getOrganisationId()); assertEquals(first.getId(), saved.getMonitoredServiceId());
        assertTrue(rules.findByOrganisationIdAndMonitoredServiceId(beta.getId(), first.getId()).isEmpty());
        mvc.perform(get(path(beta.getId(), second.getId())).with(jwt().jwt(token -> token.subject("beta-owner"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isEmpty());
        assertEquals(3, memberships.count());
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "PUT"})
    void missingAndInvalidAuthenticationAreRejected(String method) throws Exception {
        var missing = method.equals("GET") ? get(ownPath()) : put(ownPath()).contentType(MediaType.APPLICATION_JSON).content(DEFAULTS);
        mvc.perform(missing).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        var invalid = method.equals("GET") ? get(ownPath()) : put(ownPath()).contentType(MediaType.APPLICATION_JSON).content(DEFAULTS);
        mvc.perform(invalid.header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        assertEquals(0, rules.count());
    }

    @ParameterizedTest @MethodSource("invalidBodies")
    void invalidRequestsReturnExistingApiErrorAndLeaveStoredConfigurationUnchanged(String body) throws Exception {
        putRule(DEFAULTS);
        mvc.perform(put(ownPath()).with(jwt().jwt(token -> token.subject("alert-owner")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.errors").exists());
        assertEquals(1, rules.count());
        assertEquals(3, rules.findAll().getFirst().getConsecutiveFailures());
        assertEquals(AlertSeverity.HIGH, rules.findAll().getFirst().getSeverity());
    }
    static Stream<String> invalidBodies() {
        return Stream.of("{}", "null", "{", DEFAULTS.replace("true", "null"),
                DEFAULTS.replace(":3", ":0"), DEFAULTS.replace(":3", ":21"), DEFAULTS.replace(":3", ":null"),
                DEFAULTS.replace(":2", ":0"), DEFAULTS.replace(":2", ":21"), DEFAULTS.replace(":2", ":null"),
                DEFAULTS.replace("\"HIGH\"", "\"URGENT\""), DEFAULTS.replace("\"HIGH\"", "\"high\""),
                DEFAULTS.replace("\"HIGH\"", "null"), DEFAULTS.replace("\"HIGH\"", "2"),
                DEFAULTS.replace(":3", ":1.5"), DEFAULTS.replace(":2", ":1.5"),
                DEFAULTS.replace("true", "1"), DEFAULTS.replace("true", "\"true\""),
                DEFAULTS.replace(":3", ":\"3\""), DEFAULTS.replace(":2", ":2147483648"));
    }

    @Test void missingFieldsHaveFieldLevelValidationMessages() throws Exception {
        mvc.perform(put(ownPath()).with(jwt().jwt(token -> token.subject("alert-owner")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.enabled").isNotEmpty()).andExpect(jsonPath("$.errors.consecutiveFailures").isNotEmpty())
                .andExpect(jsonPath("$.errors.consecutiveRecoveries").isNotEmpty()).andExpect(jsonPath("$.errors.severity").isNotEmpty());
    }

    @ParameterizedTest @EnumSource(value = ServiceType.class, names = "HTTP", mode = EnumSource.Mode.EXCLUDE)
    void onlyHttpServicesSupportRules(ServiceType type) throws Exception {
        var other = createService(alpha, type);
        String path = path(alpha.getId(), other.getId());
        mvc.perform(get(path).with(jwt().jwt(token -> token.subject("alert-owner")))).andExpect(status().isBadRequest());
        mvc.perform(put(path).with(jwt().jwt(token -> token.subject("alert-owner")))
                        .contentType(MediaType.APPLICATION_JSON).content(DEFAULTS))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Alert rules are supported only for HTTP services."));
        assertEquals(0, rules.count());
    }

    @Test void databasePreventsDuplicateServiceRulesAndForeignOrganisationLinks() throws Exception {
        putRule(DEFAULTS);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO alert_rules(id,organisation_id,monitored_service_id) VALUES (?,?,?)
                """, UUID.randomUUID(), alpha.getId(), first.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO alert_rules(id,organisation_id,monitored_service_id) VALUES (?,?,?)
                """, UUID.randomUUID(), alpha.getId(), second.getId()));
        assertEquals(1, rules.count());
    }

    @ParameterizedTest @CsvSource({"0,2,HIGH", "21,2,HIGH", "3,0,HIGH", "3,21,HIGH", "3,2,URGENT", "3,2,high"})
    void databaseChecksCannotBeBypassed(int failures, int recoveries, String severity) {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO alert_rules(id,organisation_id,monitored_service_id,consecutive_failures,consecutive_recoveries,severity)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), alpha.getId(), first.getId(), failures, recoveries, severity));
        assertEquals(0, rules.count());
    }

    @Test void databaseDefaultsAndTimestampsRoundTripThroughJpa() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO alert_rules(id,organisation_id,monitored_service_id) VALUES (?,?,?)", id, alpha.getId(), first.getId());
        var saved = rules.findById(id).orElseThrow();
        assertTrue(saved.isEnabled()); assertEquals(3, saved.getConsecutiveFailures()); assertEquals(2, saved.getConsecutiveRecoveries());
        assertEquals(AlertSeverity.HIGH, saved.getSeverity());
        assertNotNull(saved.getCreatedAt()); assertEquals(saved.getCreatedAt(), saved.getUpdatedAt());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alert_rules SET severity=NULL WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE alert_rules SET enabled=NULL WHERE id=?", id));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE alert_rules SET updated_at=created_at - interval '1 second' WHERE id=?", id));
    }

    @Test void concurrentInitialPutsWaitForTheSameParentAndReturnOneStableRule() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(5);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), ready = new CountDownLatch(4), start = new CountDownLatch(1);
        try {
            Future<?> holder = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                services.findByIdAndOrganisationIdForUpdate(first.getId(), alpha.getId()).orElseThrow();
                locked.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture lock timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var pending = new java.util.ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 4; i++) pending.add(workers.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture start timed out");
                return putRule(DEFAULTS);
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            int waiting = 0;
            while (System.nanoTime() < deadline && waiting < 2) {
                waiting = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE '%monitored_services%'
                        """, Integer.class);
                if (waiting < 2) Thread.sleep(20);
            }
            assertTrue(waiting >= 2, "Concurrent PUTs must actually contend on the parent database row");
            assertEquals(0, rules.count());
            release.countDown(); holder.get(10, TimeUnit.SECONDS);
            JsonNode firstResponse = pending.getFirst().get(10, TimeUnit.SECONDS);
            for (Future<JsonNode> response : pending) assertEquals(firstResponse, response.get(10, TimeUnit.SECONDS));
            assertEquals(1, rules.count());
            assertEquals(firstResponse.get("id").asText(), rules.findAll().getFirst().getId().toString());
        } finally { release.countDown(); start.countDown(); workers.shutdownNow(); }
    }

    @Test void ruleAndServiceConfigurationRemainIndependentAndMonitoringStillPersists() throws Exception {
        putRule(DEFAULTS.replace("true", "false"));
        assertTrue(healthStore.enabledHttpServiceIds().contains(first.getId()));
        var target = healthStore.loadTarget(first.getId()).orElseThrow();
        healthStore.record(target, new HealthObservation(NOW, NOW, HealthStatus.UP, 200, 1, null));
        assertEquals(1, results.count());
        String healthPath = ownPath().replace("/alert-rule", "/health/latest");
        mvc.perform(get(healthPath).with(jwt().jwt(token -> token.subject("alert-owner"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        putRule(DEFAULTS);
        serviceActions.disable("alert-owner", alpha.getId(), first.getId());
        var rule = rules.findAll().getFirst();
        assertTrue(rule.isEnabled());
        assertFalse(rule.allowsEvaluation(services.findById(first.getId()).orElseThrow().isEnabled()));
        assertFalse(healthStore.enabledHttpServiceIds().contains(first.getId()));
        services.deleteById(first.getId());
        assertEquals(0, rules.count()); // The service owns its configuration/results via cascade foreign keys.
        assertEquals(0, results.count());
    }
}
