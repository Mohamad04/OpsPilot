package com.opspilot.backend.application;

import com.opspilot.backend.domain.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.net.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"opspilot.monitoring.enabled=false", "opspilot.monitoring.connect-timeout-ms=200", "opspilot.monitoring.request-timeout-ms=1000"})
@AutoConfigureMockMvc
@Testcontainers
class HealthMonitoringIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    @Autowired HealthCheckRunner runner;
    @Autowired HealthCheckStore store;
    @Autowired HealthCheckResultRepository results;
    @Autowired MonitoredServiceRepository services;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired UserRepository users;
    @Autowired MonitoredServicesService serviceActions;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;
    // Only this fixture policy maps test hosts to loopback. Production has no private-network bypass.
    // HealthTargetPolicyTest independently exercises the real public-address policy.
    @MockitoBean HealthTargetPolicy policy;
    private HttpServer server;
    private ExecutorService handlers;
    private Organisation alpha, beta;
    private MonitoredService first, second;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach void fixtures() throws Exception {
        results.deleteAll(); services.deleteAll(); memberships.deleteAll(); organisations.deleteAll(); users.deleteAll();
        alpha = organisations.save(Organisation.create("Alpha", "alpha-health"));
        beta = organisations.save(Organisation.create("Beta", "beta-health"));
        User alice = users.save(User.create("alice-health", "Alice", "alice-health@example.test"));
        memberships.save(OrganisationMembership.create(alpha, alice, OrganisationRole.OWNER));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool(); server.setExecutor(handlers);
        server.createContext("/api/health", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.createContext("/api/down", exchange -> { hits.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/api/redirect", exchange -> {
            hits.incrementAndGet(); exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        when(policy.validate(anyString(), anyString())).thenAnswer(call -> {
            String endpoint = call.getArgument(1);
            if (endpoint.equals("/blocked")) throw new HealthTargetPolicy.BlockedTargetException();
            URI uri = URI.create(call.<String>getArgument(0) + endpoint);
            return new HealthTargetPolicy.Target(uri, "fixture.example.test", server.getAddress().getPort(), InetAddress.getByName("127.0.0.1"));
        });
        first = create(alpha, "/health", ServiceType.HTTP, true);
        second = create(beta, "/health", ServiceType.HTTP, true);
    }
    @AfterEach void close() { server.stop(0); handlers.shutdownNow(); }

    private MonitoredService create(Organisation organisation, String endpoint, ServiceType type, boolean enabled) {
        var service = MonitoredService.create(organisation, "Health fixture", type, Environment.TEST,
                "http://fixture.example.test:" + server.getAddress().getPort() + "/api", endpoint, "Test team");
        if (!enabled) service.disable();
        return services.save(service);
    }
    private String path(UUID tenant, UUID service) { return "/api/organisations/" + tenant + "/monitored-services/" + service + "/health"; }

    @Test void firstCheckPersistsAndLatestSeparatesEnabledFromObservedHealth() throws Exception {
        String path = path(alpha.getId(), first.getId());
        mvc.perform(get(path + "/latest").with(jwt().jwt(token -> token.subject("alice-health"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.latestResult").isEmpty());
        runner.check(first.getId());
        var saved = results.findAll().getFirst();
        assertEquals(alpha.getId(), saved.getOrganisationId());
        assertEquals(first.getId(), saved.getMonitoredServiceId());
        assertEquals(HealthStatus.UP, saved.getStatus());
        assertEquals(204, saved.getHttpStatusCode());
        assertNotNull(saved.getStartedAt()); assertNotNull(saved.getCompletedAt());
        assertTrue(saved.getResponseTimeMs() >= 0); assertNull(saved.getFailureDetail());
        mvc.perform(get(path + "/latest").with(jwt().jwt(token -> token.subject("alice-health"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.latestResult.httpStatusCode").value(204));
        serviceActions.disable("alice-health", alpha.getId(), first.getId());
        runner.check(first.getId());
        assertEquals(1, results.count());
        mvc.perform(get(path + "/latest").with(jwt().jwt(token -> token.subject("alice-health"))))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.status").value("UP")); // Historical observation.
    }

    @Test void schedulerSelectsOnlyEnabledHttpServicesAndPersistsTheirOwnTenant() {
        var disabled = create(alpha, "/health", ServiceType.HTTP, false);
        var tcp = create(alpha, "/health", ServiceType.TCP, true);
        assertEquals(2, store.enabledHttpServiceIds().size());
        new HealthCheckScheduler(store, runner).runChecks();
        assertEquals(2, hits.get()); assertEquals(2, results.count());
        assertTrue(results.findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(alpha.getId(), disabled.getId()).isEmpty());
        assertTrue(results.findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(alpha.getId(), tcp.getId()).isEmpty());
        assertTrue(results.findFirstByOrganisationIdAndMonitoredServiceIdOrderByStartedAtDescIdDesc(beta.getId(), first.getId()).isEmpty());
    }

    @Test void failedHttpAndBlockedTargetsPersistBoundedFailureDetails() {
        for (String endpoint : List.of("/down", "/redirect", "/blocked")) runner.check(create(alpha, endpoint, ServiceType.HTTP, true).getId());
        assertEquals(3, results.count()); assertEquals(2, hits.get());
        assertTrue(results.findAll().stream().anyMatch(result -> result.getStatus() == HealthStatus.DOWN && Integer.valueOf(503).equals(result.getHttpStatusCode())));
        assertTrue(results.findAll().stream().anyMatch(result -> result.getStatus() == HealthStatus.DOWN && Integer.valueOf(302).equals(result.getHttpStatusCode())));
        assertTrue(results.findAll().stream().anyMatch(result -> result.getStatus() == HealthStatus.UNKNOWN && result.getHttpStatusCode() == null));
        assertTrue(results.findAll().stream().allMatch(result -> result.getFailureDetail() != null && result.getFailureDetail().length() <= 255));
    }

    @Test void timeoutIsPersistedOnceAndDoesNotPreventSubsequentChecks() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/api/slow", exchange -> {
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try {
            var slow = create(alpha, "/slow", ServiceType.HTTP, true);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () -> runner.check(slow.getId()));
            var saved = results.findAll().getFirst();
            assertEquals(HealthStatus.DOWN, saved.getStatus()); assertNull(saved.getHttpStatusCode());
            assertEquals("HTTP check timed out.", saved.getFailureDetail());
            runner.check(first.getId());
            assertEquals(2, results.count());
        } finally { release.countDown(); }
    }

    @Test void historyIsLimitedNewestFirstAndDoesNotDependOnCompletionOrder() throws Exception {
        Instant start = Instant.parse("2026-10-26T12:00:00Z");
        for (int i : new int[]{2, 3, 1}) results.save(new HealthCheckResult(alpha.getId(), first.getId(), start.plusSeconds(i),
                start.plusSeconds(10), HealthStatus.UP, 200, 1, null));
        results.save(new HealthCheckResult(beta.getId(), second.getId(), start.plusSeconds(20), start.plusSeconds(21), HealthStatus.DOWN, 500, 1, "Unexpected status"));
        String path = path(alpha.getId(), first.getId());
        mvc.perform(get(path + "/history?limit=2").with(jwt().jwt(token -> token.subject("alice-health"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].startedAt").value(start.plusSeconds(3).toString()))
                .andExpect(jsonPath("$[1].startedAt").value(start.plusSeconds(2).toString()))
                .andExpect(jsonPath("$[0].organisationId").value(alpha.getId().toString()));
        mvc.perform(get(path + "/latest").with(jwt().jwt(token -> token.subject("alice-health"))))
                .andExpect(jsonPath("$.latestResult.startedAt").value(start.plusSeconds(3).toString()));
        for (String limit : List.of("0", "101", "-1", "bad")) {
            mvc.perform(get(path + "/history?limit=" + limit).with(jwt().jwt(token -> token.subject("alice-health"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request parameter"));
        }
    }

    @Test void databaseRejectsAResultWithAnotherTenantsServiceId() {
        Timestamp timestamp = Timestamp.from(Instant.now());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO health_check_results(id,organisation_id,monitored_service_id,started_at,completed_at,status,response_time_ms)
                VALUES (?,?,?,?,?,'UP',0)
                """, UUID.randomUUID(), beta.getId(), first.getId(), timestamp, timestamp));
        assertEquals(0, results.count());
    }

    @Test void configurationChangedAfterCheckStartedDiscardsTheOldObservation() {
        HealthCheckTarget target = store.loadTarget(first.getId()).orElseThrow();
        serviceActions.disable("alice-health", alpha.getId(), first.getId());
        Instant now = Instant.now();
        store.record(target, new HealthObservation(now, now, HealthStatus.UP, 200, 1, null));
        assertEquals(0, results.count());
    }
}
