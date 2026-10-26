package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
class AlertManagementIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    private static final Instant START = Instant.parse("2026-10-27T12:00:00.123456Z");
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AlertRepository alerts;
    @Autowired AlertEvaluationStateRepository states;
    @Autowired AlertRuleRepository rules;
    @Autowired HealthCheckResultRepository results;
    @Autowired MonitoredServiceRepository services;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired UserRepository users;
    @Autowired HealthCheckStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;
    private Organisation alpha, beta;
    private MonitoredService first, sibling, foreign;
    private OrganisationMembership membership;

    @BeforeEach void fixtures() {
        alerts.deleteAll(); states.deleteAll(); results.deleteAll(); rules.deleteAll(); services.deleteAll();
        memberships.deleteAll(); organisations.deleteAll(); users.deleteAll();
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Invalid fixture token"));
        alpha = organisations.save(Organisation.create("Alert API Alpha", "alert-api-alpha"));
        beta = organisations.save(Organisation.create("Alert API Beta", "alert-api-beta"));
        var owner = users.save(User.create("alert-reader", "Reader", "alert-reader@example.test"));
        users.save(User.create("alert-outsider", "Outsider", "alert-outsider@example.test"));
        membership = memberships.save(OrganisationMembership.create(alpha, owner, OrganisationRole.OWNER));
        memberships.save(OrganisationMembership.create(beta, owner, OrganisationRole.VIEWER));
        first = service(alpha); sibling = service(alpha); foreign = service(beta);
    }
    private MonitoredService service(Organisation organisation) {
        return services.save(MonitoredService.create(organisation, "Alert API fixture", ServiceType.HTTP,
                Environment.TEST, "https://fixture.example.test", "/health", "Test team"));
    }
    private HealthCheckResult evidence(MonitoredService service, Instant at, HealthStatus status) {
        return results.save(new HealthCheckResult(service.getOrganisation().getId(), service.getId(), at, at.plusMillis(4),
                status, status == HealthStatus.DOWN ? 503 : 200, 4, "Private fixture diagnostic; never return this text"));
    }
    private Alert alert(MonitoredService service, AlertSeverity severity, int second, boolean resolved) {
        var down = evidence(service, START.plusSeconds(second), HealthStatus.DOWN);
        Alert alert = Alert.open(down, down.getId(), down.getStartedAt(), 1, 1, 2, severity);
        if (resolved) alert.resolve(evidence(service, down.getStartedAt().plusSeconds(2), HealthStatus.UP), 2);
        return alerts.save(alert);
    }
    private String base() { return "/api/organisations/" + alpha.getId() + "/alerts"; }
    private String history(UUID service) { return "/api/organisations/" + alpha.getId() + "/monitored-services/" + service + "/alerts"; }
    private JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(jwt().jwt(token -> token.subject("alert-reader"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>(); for (var entry : page.get("content")) ids.add(entry.get("id").asText()); return ids;
    }
    private List<String> snapshot() {
        var data = new ArrayList<String>();
        for (String table : List.of("alerts", "alert_evaluation_states", "health_check_results", "alert_rules",
                "monitored_services", "users", "organisations", "organisation_memberships")) {
            // Identifiers are a fixed fixture-only allowlist; no request input enters SQL.
            data.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id", String.class));
        }
        return data;
    }

    @Test void emptyPagesAndSummaryHaveStableDefaultsAndExplicitZeroSeverities() throws Exception {
        JsonNode page = read(base());
        assertTrue(page.get("content").isEmpty()); assertEquals(0, page.get("page").asInt()); assertEquals(20, page.get("size").asInt());
        assertEquals(0, page.get("totalElements").asLong()); assertEquals(0, page.get("totalPages").asInt());
        assertTrue(page.get("first").asBoolean()); assertTrue(page.get("last").asBoolean());
        assertEquals(page, read(history(first.getId())));
        JsonNode summary = read(base() + "/summary"); assertEquals(0, summary.get("totalAlerts").asLong());
        assertEquals(0, summary.get("activeAlerts").asLong()); assertEquals(0, summary.get("resolvedAlerts").asLong());
        assertEquals(4, summary.get("activeBySeverity").size());
        for (var severity : AlertSeverity.values()) assertEquals(0, summary.get("activeBySeverity").get(severity.name()).asLong());
        assertEquals(0, alerts.count()); assertEquals(0, states.count());
    }

    @Test void activeAndResolvedDetailsExposePersistedEvidenceWithoutInternalDiagnostics() throws Exception {
        var active = alert(first, AlertSeverity.CRITICAL, 1, false);
        var resolved = alert(first, AlertSeverity.LOW, 0, true);
        assertEquals(List.of(active.getId().toString(), resolved.getId().toString()), ids(read(base())));
        for (var alert : List.of(active, resolved)) {
            JsonNode detail = read(base() + "/" + alert.getId());
            assertEquals(alert.getId().toString(), detail.get("id").asText());
            assertEquals(alpha.getId().toString(), detail.get("organisationId").asText());
            assertEquals(first.getId().toString(), detail.get("monitoredServiceId").asText());
            assertEquals("HTTP_AVAILABILITY", detail.get("category").asText());
            assertEquals(alert.getSeverity().name(), detail.get("severity").asText());
            assertEquals(alert.getStatus().name(), detail.get("status").asText());
            assertEquals(alert.getFirstSeenAt(), Instant.parse(detail.get("firstSeenAt").asText()));
            assertEquals(alert.getLastSeenAt(), Instant.parse(detail.get("lastSeenAt").asText()));
            assertEquals(1, detail.get("occurrenceCount").asLong());
            assertEquals(1, detail.get("failureThreshold").asInt()); assertEquals(2, detail.get("recoveryThreshold").asInt());
            assertEquals(alert.getFirstFailureHealthCheckResultId().toString(), detail.get("firstFailureHealthCheckResultId").asText());
            assertEquals(alert.getTriggeringHealthCheckResultId().toString(), detail.get("triggeringHealthCheckResultId").asText());
            assertEquals(alert.getLastFailureHealthCheckResultId().toString(), detail.get("lastFailureHealthCheckResultId").asText());
            assertEquals(503, detail.get("triggeringHttpStatusCode").asInt()); assertEquals(4, detail.get("triggeringResponseTimeMs").asLong());
            assertFalse(detail.has("triggeringFailureDetail")); assertFalse(detail.toString().contains("Private fixture diagnostic"));
        }
        JsonNode open = read(base() + "/" + active.getId());
        assertTrue(open.get("resolvedAt").isNull()); assertTrue(open.get("resolvedAfterRecoveries").isNull());
        assertTrue(open.get("resolutionHealthCheckResultId").isNull());
        JsonNode closed = read(base() + "/" + resolved.getId());
        assertEquals(resolved.getResolvedAt(), Instant.parse(closed.get("resolvedAt").asText()));
        assertEquals(2, closed.get("resolvedAfterRecoveries").asInt());
        assertEquals(resolved.getResolutionHealthCheckResultId().toString(), closed.get("resolutionHealthCheckResultId").asText());
    }

    @Test void stableUuidTieBreakAndPaginationUseDatabaseOrdering() throws Exception {
        var firstAlert = alert(first, AlertSeverity.HIGH, 0, true);
        var secondAlert = alert(first, AlertSeverity.HIGH, 0, true);
        var latest = alert(sibling, AlertSeverity.MEDIUM, 2, false);
        List<String> ties = new ArrayList<>(List.of(firstAlert.getId().toString(), secondAlert.getId().toString()));
        ties.sort(java.util.Comparator.reverseOrder());
        List<String> expected = new ArrayList<>(List.of(latest.getId().toString())); expected.addAll(ties);
        var combined = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            JsonNode page = read(base() + "?size=1&page=" + i); combined.addAll(ids(page));
            assertEquals(i, page.get("page").asInt()); assertEquals(1, page.get("size").asInt());
            assertEquals(3, page.get("totalElements").asLong()); assertEquals(3, page.get("totalPages").asInt());
            assertEquals(i == 0, page.get("first").asBoolean()); assertEquals(i == 2, page.get("last").asBoolean());
        }
        assertEquals(expected, combined); assertEquals(expected, ids(read(base() + "?size=100")));
        JsonNode beyond = read(base() + "?size=1&page=3"); assertTrue(beyond.get("content").isEmpty());
        assertEquals(3, beyond.get("totalElements").asLong()); assertTrue(beyond.get("last").asBoolean());
    }

    @Test void defaultAndMaximumPageSizesBoundLargerHistories() throws Exception {
        for (int i = 0; i < 25; i++) alert(first, AlertSeverity.HIGH, i, true);
        alert(first, AlertSeverity.HIGH, 30, false);
        assertEquals(20, read(base()).get("content").size());
        assertEquals(20, read(history(first.getId())).get("content").size());
        JsonNode secondPage = read(base() + "?page=1");
        assertEquals(6, secondPage.get("content").size()); assertEquals(26, secondPage.get("totalElements").asLong());
        assertEquals(2, secondPage.get("totalPages").asInt());
        assertEquals(26, read(base() + "?size=100").get("content").size());
    }

    @Test void individualAndCombinedFiltersAreScopedAndShareServiceHistorySemantics() throws Exception {
        var resolved = alert(first, AlertSeverity.LOW, 0, true);
        var active = alert(first, AlertSeverity.HIGH, 4, false);
        var other = alert(sibling, AlertSeverity.CRITICAL, 8, false);
        alert(foreign, AlertSeverity.HIGH, 10, false);
        assertEquals(List.of(resolved.getId().toString()), ids(read(base() + "?status=RESOLVED")));
        assertEquals(List.of(other.getId().toString(), active.getId().toString()), ids(read(base() + "?status=ACTIVE")));
        assertEquals(List.of(active.getId().toString()), ids(read(base() + "?severity=HIGH")));
        String serviceFilter = "?serviceId=" + first.getId();
        assertEquals(List.of(active.getId().toString(), resolved.getId().toString()), ids(read(base() + serviceFilter)));
        assertEquals(read(base() + serviceFilter), read(history(first.getId())));
        String range = "&from=" + START.plusSeconds(4) + "&to=" + START.plusSeconds(8);
        assertEquals(List.of(active.getId().toString()), ids(read(base() + serviceFilter + "&status=ACTIVE&severity=HIGH" + range)));
        assertEquals(List.of(active.getId().toString()), ids(read(history(first.getId()) + "?status=ACTIVE&severity=HIGH" + range)));
        assertTrue(read(base() + "?status=RESOLVED&severity=CRITICAL").get("content").isEmpty());
    }

    @Test void dateRangeUsesFirstSeenInclusiveFromAndExclusiveToIncludingNanoPrecision() throws Exception {
        var older = alert(first, AlertSeverity.LOW, 0, true);
        var newer = alert(first, AlertSeverity.HIGH, 1, false);
        assertEquals(List.of(newer.getId().toString()), ids(read(base() + "?from=" + START.plusNanos(1))));
        assertEquals(List.of(older.getId().toString()), ids(read(base() + "?to=" + START.plusNanos(1))));
        assertEquals(List.of(older.getId().toString()), ids(read(base() + "?from=" + START + "&to=" + START.plusSeconds(1))));
        assertEquals(List.of(newer.getId().toString()), ids(read(base() + "?from=" + START.plusSeconds(1))));
        assertTrue(read(base() + "?to=" + START).get("content").isEmpty());
        // MockMvc param() models the decoded query value, without double-encoding a literal '%'.
        JsonNode offset = json.readTree(mvc.perform(get(base()).param("from", "2026-10-27T13:00:00.123456+01:00")
                        .with(jwt().jwt(token -> token.subject("alert-reader"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(ids(read(base() + "?from=" + START)), ids(offset));
    }

    @ParameterizedTest @ValueSource(strings = {"page=-1", "size=0", "size=-1", "size=101", "page=not-a-number",
            "page=2147483648", "size=2147483648", "page=2147483647&size=100", "page=1.5",
            "status=BROKEN", "status=active", "severity=URGENT", "severity=high", "serviceId=invalid",
            "from=not-a-date", "to=2026-02-30T00:00:00Z", "from=2026-10-27", "to=2026-10-27T12:00:00",
            "from=2026-10-27T12:00:00Z&to=2026-10-27T12:00:00Z",
            "from=2026-10-28T12:00:00Z&to=2026-10-27T12:00:00Z", "from=0000-01-01T00:00:00Z"})
    void invalidFiltersReturnExistingSafeErrorFormat(String parameters) throws Exception {
        String before = snapshot().toString();
        for (String path : List.of(base(), history(first.getId()))) {
            // serviceId is an organisation-list filter; the service history fixes it in its path.
            if (parameters.startsWith("serviceId") && path.equals(history(first.getId()))) continue;
            mvc.perform(get(path + "?" + parameters).with(jwt().jwt(token -> token.subject("alert-reader"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errors").isEmpty());
        }
        assertEquals(before, snapshot().toString());
    }

    @Test void foreignAndMissingAlertsAndServicesHaveIndistinguishableNotFoundResponses() throws Exception {
        var hidden = alert(foreign, AlertSeverity.CRITICAL, 1, false);
        String missing = mvc.perform(get(base() + "/" + UUID.randomUUID()).with(jwt().jwt(token -> token.subject("alert-reader"))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String foreignResponse = mvc.perform(get(base() + "/" + hidden.getId()).with(jwt().jwt(token -> token.subject("alert-reader"))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(missing, foreignResponse); assertFalse(foreignResponse.contains(beta.getId().toString()));
        for (UUID service : List.of(foreign.getId(), UUID.randomUUID())) {
            for (String path : List.of(history(service), base() + "?serviceId=" + service)) {
                mvc.perform(get(path).with(jwt().jwt(token -> token.subject("alert-reader"))))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
            }
        }
    }

    @ParameterizedTest @EnumSource(OrganisationRole.class)
    void everyLocalRoleCanReadAllFourEndpoints(OrganisationRole role) throws Exception {
        membership.changeRole(role); memberships.save(membership);
        var alert = alert(first, AlertSeverity.HIGH, 0, false);
        for (String path : List.of(base(), base() + "/summary", base() + "/" + alert.getId(), history(first.getId()))) read(path);
    }

    @Test void nonMembersCannotDistinguishMissingAndExistingOrganisations() throws Exception {
        var alert = alert(first, AlertSeverity.HIGH, 0, false);
        for (String suffix : List.of("/alerts", "/alerts/summary", "/alerts/" + alert.getId(),
                "/monitored-services/" + first.getId() + "/alerts")) {
            String denied = mvc.perform(get("/api/organisations/" + alpha.getId() + suffix)
                            .with(jwt().jwt(token -> token.subject("alert-outsider"))))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String absent = mvc.perform(get("/api/organisations/" + UUID.randomUUID() + suffix)
                            .with(jwt().jwt(token -> token.subject("alert-outsider"))))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertEquals(absent, denied); assertEquals("Organisation membership not found", json.readTree(denied).get("message").asText());
        }
    }

    @Test void missingAndInvalidJwtAreRejectedForAllEndpoints() throws Exception {
        for (String path : List.of(base(), base() + "/summary", base() + "/" + UUID.randomUUID(), history(first.getId()))) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
    }

    @Test void inactiveAndUnprovisionedIdentitiesCannotReadAlerts() throws Exception {
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE identity_provider_subject=?", "alert-reader");
        for (String path : List.of(base(), base() + "/summary", base() + "/" + UUID.randomUUID(), history(first.getId()))) {
            mvc.perform(get(path).with(jwt().jwt(token -> token.subject("alert-reader"))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
            mvc.perform(get(path).with(jwt().jwt(token -> token.subject("unprovisioned-identity"))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("User not found"));
        }
    }

    @Test void alertRoutesExposeNoMutationOperations() throws Exception {
        var alert = alert(first, AlertSeverity.HIGH, 0, false);
        List<String> before = snapshot();
        for (String path : List.of(base(), base() + "/summary", base() + "/" + alert.getId(), history(first.getId()))) {
            for (var method : List.of(org.springframework.http.HttpMethod.POST, org.springframework.http.HttpMethod.PUT,
                    org.springframework.http.HttpMethod.PATCH, org.springframework.http.HttpMethod.DELETE)) {
                mvc.perform(request(method, path).with(jwt().jwt(token -> token.subject("alert-reader"))))
                        .andExpect(status().isMethodNotAllowed());
            }
        }
        assertEquals(before, snapshot());
    }

    @Test void summaryAggregatesBothStatusesAndEveryActiveSeverityWithoutForeignRows() throws Exception {
        int second = 0;
        for (var severity : AlertSeverity.values()) {
            alert(service(alpha), severity, ++second, false);
            alert(first, severity, ++second, true);
        }
        alert(service(alpha), AlertSeverity.HIGH, ++second, false);
        alert(foreign, AlertSeverity.CRITICAL, ++second, false);
        var summary = read(base() + "/summary");
        assertEquals(9, summary.get("totalAlerts").asLong()); assertEquals(5, summary.get("activeAlerts").asLong());
        assertEquals(4, summary.get("resolvedAlerts").asLong()); assertEquals(4, summary.get("activeBySeverity").size());
        for (var severity : AlertSeverity.values()) assertEquals(severity == AlertSeverity.HIGH ? 2 : 1,
                summary.get("activeBySeverity").get(severity.name()).asLong());
    }

    @Test void readsNeverMutateAnyBusinessTableAndEvaluationStillFeedsTheApi() throws Exception {
        var target = store.loadTarget(first.getId()).orElseThrow();
        for (int i = 0; i < 3; i++) store.record(target, new HealthObservation(START.plusSeconds(i), START.plusSeconds(i).plusMillis(4),
                HealthStatus.DOWN, 503, 4, "Unexpected HTTP status: 503."));
        Alert active = alerts.findAll().getFirst(); assertEquals(3, active.getOccurrenceCount());
        List<String> before = snapshot();
        for (int i = 0; i < 2; i++) {
            read(base()); read(base() + "/summary"); read(base() + "/" + active.getId()); read(history(first.getId()));
        }
        assertEquals(before, snapshot());
        for (int i = 3; i < 5; i++) store.record(target, new HealthObservation(START.plusSeconds(i), START.plusSeconds(i).plusMillis(4),
                HealthStatus.UP, 200, 4, null));
        assertEquals("RESOLVED", read(base() + "/" + active.getId()).get("status").asText());
        assertEquals(1, read(base() + "/summary").get("resolvedAlerts").asLong());
        assertEquals(1, alerts.count());
    }
}
