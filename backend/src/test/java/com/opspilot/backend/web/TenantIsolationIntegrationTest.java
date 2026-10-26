package com.opspilot.backend.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.opspilot.backend.domain.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Real HTTP -> resource-server JWT validation -> controllers/services -> migrated PostgreSQL.
// The database, signing keys and JWKS server are disposable and never use local business data.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "opspilot.monitoring.enabled=false")
@Testcontainers
class TenantIsolationIntegrationTest {
    private static final String ISSUER = "https://tenant-test.example/realms/opspilot";
    private static final KeyPair KEYS = keys();
    private static final HttpServer JWKS = jwks();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREATE = """
            {"name":"%s","serviceType":"HTTP","environment":"PRODUCTION",
             "baseUrl":"https://%s.example.test","healthEndpoint":"/health","owner":"Test team"}
            """;

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired OrganisationRepository organisations;
    @Autowired OrganisationMembershipRepository memberships;
    @Autowired MonitoredServiceRepository services;
    @Autowired HealthCheckResultRepository healthResults;
    @Autowired AlertRuleRepository alertRules;

    private UUID alpha, beta, alphaService, betaService;
    private String alice, bob, dual, outsider;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        properties.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "opspilot-backend");
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll static void closeJwks() { JWKS.stop(0); }

    @BeforeEach
    void fixtures() throws Exception {
        services.deleteAll();
        memberships.deleteAll();
        organisations.deleteAll();
        users.deleteAll();
        alice = token("alice", ISSUER, "opspilot-backend", KEYS, 300);
        bob = token("bob", ISSUER, "opspilot-backend", KEYS, 300);
        dual = token("dual", ISSUER, "opspilot-backend", KEYS, 300);
        outsider = token("outsider", ISSUER, "opspilot-backend", KEYS, 300);
        for (String identity : List.of(alice, bob, dual, outsider)) {
            expect(call("POST", "/api/me/provision", identity, null), 200);
            assertEquals("[]", expect(call("GET", "/api/organisations", identity, null), 200).body());
        }
        alpha = id(expect(call("POST", "/api/organisations", alice,
                "{\"name\":\"Alpha isolated\",\"slug\":\"alpha-isolated\"}"), 201));
        beta = id(expect(call("POST", "/api/organisations", bob,
                "{\"name\":\"Beta confidential\",\"slug\":\"beta-confidential\"}"), 201));
        User shared = users.findByIdentityProviderSubject("dual").orElseThrow();
        memberships.save(OrganisationMembership.create(organisations.findById(alpha).orElseThrow(), shared, OrganisationRole.ENGINEER));
        memberships.save(OrganisationMembership.create(organisations.findById(beta).orElseThrow(), shared, OrganisationRole.VIEWER));
        alphaService = id(expect(call("POST", base(alpha), alice, CREATE.formatted("Alpha API", "alpha")), 201));
        UUID disabled = id(expect(call("POST", base(alpha), alice, CREATE.formatted("Alpha worker", "alpha-worker")), 201));
        expect(call("PATCH", base(alpha) + "/" + disabled + "/disable", alice, null), 200);
        betaService = id(expect(call("POST", base(beta), bob, CREATE.formatted("Beta secret API", "beta-secret")), 201));
    }

    @Test
    void membershipListsAndDashboardSourceContainOnlyAuthorisedTenantData() throws Exception {
        for (var entry : List.of(new Object[]{alice, alpha}, new Object[]{bob, beta})) {
            JsonNode list = JSON.readTree(expect(call("GET", "/api/organisations", (String) entry[0], null), 200).body());
            assertEquals(1, list.size());
            assertEquals(entry[1].toString(), list.get(0).get("id").asText());
        }
        assertEquals(2, JSON.readTree(expect(call("GET", "/api/organisations", dual, null), 200).body()).size());
        assertEquals("[]", expect(call("GET", "/api/organisations", outsider, null), 200).body());
        // Dashboard uses this same collection endpoint; count actual configuration states.
        for (UUID tenant : List.of(alpha, beta, alpha, beta)) {
            JsonNode list = JSON.readTree(expect(call("GET", base(tenant), dual, null), 200).body());
            assertEquals(tenant.equals(alpha) ? 2 : 1, list.size());
            int enabled = 0;
            for (JsonNode service : list) {
                assertEquals(tenant.toString(), service.get("organisationId").asText());
                if (service.get("enabled").asBoolean()) enabled++;
            }
            assertEquals(1, enabled);
        }
        assertEquals(0, memberships.findAllByUserId(users.findByIdentityProviderSubject("outsider").orElseThrow().getId()).size());
    }

    @ParameterizedTest @ValueSource(strings = {"organisation", "list", "read", "create", "enable", "disable", "alert-read", "alert-write"})
    void unauthorisedTenantAndUnknownTenantAreIndistinguishable(String operation) throws Exception {
        HttpResponse<String> denied = operation(operation, beta, betaService, alice);
        HttpResponse<String> absent = operation(operation, UUID.randomUUID(), UUID.randomUUID(), alice);
        expect(denied, 404);
        expect(absent, 404);
        assertEquals(absent.body(), denied.body());
        assertEquals("Organisation membership not found", JSON.readTree(denied.body()).get("message").asText());
        expect(operation(operation, alpha, alphaService, bob), 404);
        expect(operation(operation, alpha, alphaService, outsider), 404);
        assertUnchanged();
    }

    @ParameterizedTest @ValueSource(strings = {"read", "enable", "disable", "alert-read", "alert-write"})
    void serviceIdsCannotCrossTenantPathsEvenForAMemberOfBoth(String operation) throws Exception {
        for (String identity : List.of(alice, dual)) {
            HttpResponse<String> response = operation(operation, alpha, betaService, identity);
            expect(response, 404);
            JsonNode error = JSON.readTree(response.body());
            assertEquals(3, error.size());
            assertTrue(error.get("errors").isEmpty());
            assertFalse(response.body().contains("Beta secret"));
            assertFalse(response.body().contains("beta-secret.example.test"));
        }
        assertUnchanged();
    }

    @ParameterizedTest @ValueSource(strings = {"create", "enable", "disable", "alert-write"})
    void rolesAreLocalToEachOrganisationAndCannotBeElevatedByJwtClaims(String operation) throws Exception {
        // The same signed token claims OWNER, but the database gives this user VIEWER in Beta.
        expect(operation(operation, beta, betaService, dual), 403);
        expect(operation(operation, beta, UUID.randomUUID(), dual), 403);
        assertUnchanged();
    }

    @Test
    void registrationAndUpdatesAffectOnlyTheAuthorisedTenant() throws Exception {
        UUID created = id(expect(call("POST", base(alpha), dual,
                CREATE.formatted("Created only in Alpha", "new-alpha").stripTrailing().replace("}",
                        ",\"organisationId\":\"" + beta + "\",\"userId\":\"bob\",\"role\":\"OWNER\"}")), 201));
        assertEquals(alpha, services.findById(created).orElseThrow().getOrganisation().getId());
        expect(call("PATCH", base(alpha) + "/" + created + "/disable", dual, null), 200);
        assertFalse(services.findById(created).orElseThrow().isEnabled());
        expect(call("PATCH", base(alpha) + "/" + created + "/enable", dual, null), 200);
        assertTrue(services.findById(created).orElseThrow().isEnabled());
        assertEquals(1, services.findAllByOrganisationId(beta).size());
        assertTrue(services.findById(betaService).orElseThrow().isEnabled());
        expect(call("GET", base(beta) + "/" + betaService, bob, null), 200);
        assertEquals(4, memberships.count());
    }

    @Test
    void revokedMembershipIsEnforcedOnTheNextRequestWithTheSameValidJwt() throws Exception {
        User formerOwner = users.findByIdentityProviderSubject("alice").orElseThrow();
        memberships.deleteAll(memberships.findAllByUserId(formerOwner.getId()));
        assertEquals("[]", expect(call("GET", "/api/organisations", alice, null), 200).body());
        for (String operation : List.of("organisation", "list", "read", "create", "enable", "disable", "alert-read", "alert-write")) {
            expect(operation(operation, alpha, alphaService, alice), 404);
        }
        assertEquals(3, services.count());
        assertTrue(services.findById(alphaService).orElseThrow().isEnabled());
        expect(call("GET", base(alpha), dual, null), 200);
    }

    @Test
    void alertRulesRemainDistinctAcrossAuthorisedTenantsWithRealJwtRequests() throws Exception {
        JsonNode defaults = JSON.readTree(expect(operation("alert-read", alpha, alphaService, alice), 200).body());
        assertTrue(defaults.get("id").isNull());
        assertEquals(0, alertRules.count());
        String alphaPath = base(alpha) + "/" + alphaService + "/alert-rule";
        String betaPath = base(beta) + "/" + betaService + "/alert-rule";
        String alphaConfiguration = "{\"enabled\":true,\"consecutiveFailures\":4,\"consecutiveRecoveries\":2,\"severity\":\"LOW\"}";
        String betaConfiguration = "{\"enabled\":false,\"consecutiveFailures\":1,\"consecutiveRecoveries\":1,\"severity\":\"CRITICAL\"}";
        String first = expect(call("PUT", alphaPath, dual, alphaConfiguration), 200).body(); // ENGINEER in Alpha.
        expect(call("PUT", betaPath, bob, betaConfiguration), 200);
        assertEquals(JSON.readTree(first), JSON.readTree(expect(call("PUT", alphaPath, dual, alphaConfiguration), 200).body()));
        for (UUID tenant : List.of(alpha, beta, alpha, beta)) {
            UUID service = tenant.equals(alpha) ? alphaService : betaService;
            JsonNode configuration = JSON.readTree(expect(operation("alert-read", tenant, service, dual), 200).body());
            assertEquals(tenant.toString(), configuration.get("organisationId").asText());
            assertEquals(service.toString(), configuration.get("monitoredServiceId").asText());
            assertEquals(tenant.equals(alpha) ? "LOW" : "CRITICAL", configuration.get("severity").asText());
            assertEquals(tenant.equals(alpha), configuration.get("enabled").asBoolean());
        }
        assertEquals(2, alertRules.count());
        assertEquals(4, memberships.count());
    }

    @Test
    void healthApisValidateJwtAndMembershipAndNeverReturnAnotherTenantsObservations() throws Exception {
        Instant now = Instant.now();
        healthResults.save(new HealthCheckResult(alpha, alphaService, now, now, HealthStatus.UP, 200, 2, null));
        healthResults.save(new HealthCheckResult(beta, betaService, now, now, HealthStatus.DOWN, 503, 5, "Unexpected HTTP status: 503."));
        for (String suffix : List.of("latest", "history")) {
            String own = base(alpha) + "/" + alphaService + "/health/" + suffix;
            String foreign = base(beta) + "/" + betaService + "/health/" + suffix;
            expect(call("GET", own, null, null), 401);
            expect(call("GET", own, "invalid", null), 401);
            expect(call("GET", own, token("alice", ISSUER, "wrong-api", KEYS, 300), null), 401);
            expect(call("GET", own, alice, null), 200);
            expect(call("GET", foreign, bob, null), 200);
            // The dual member can read Beta health as VIEWER, without any write privilege.
            assertTrue(expect(call("GET", foreign, dual, null), 200).body().contains("DOWN"));
            HttpResponse<String> denied = expect(call("GET", foreign, alice, null), 404);
            assertFalse(denied.body().contains("503"));
            assertEquals(denied.body(), expect(call("GET", base(UUID.randomUUID()) + "/" + betaService + "/health/" + suffix, alice, null), 404).body());
            expect(call("GET", own, outsider, null), 404);
            // Being a member of both organisations still cannot move service IDs between paths.
            expect(call("GET", base(alpha) + "/" + betaService + "/health/" + suffix, dual, null), 404);
            assertFalse(expect(call("GET", own, alice, null), 200).body().contains(beta.toString()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"organisation", "list", "read", "create", "enable", "disable", "alert-read", "alert-write"})
    void everyScopedOperationRejectsMissingMalformedAndInvalidSignedTokens(String operation) throws Exception {
        for (String identity : new String[]{null, "invalid",
                token("alice", ISSUER, "wrong-api", KEYS, 300),
                token("alice", "https://wrong-issuer.example", "opspilot-backend", KEYS, 300),
                token("alice", ISSUER, "opspilot-backend", KEYS, -300),
                token("alice", ISSUER, "opspilot-backend", keys(), 300)}) {
            expect(operation(operation, alpha, alphaService, identity), 401);
        }
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertEquals(3, services.count());
        assertEquals(4, memberships.count());
        assertTrue(services.findById(alphaService).orElseThrow().isEnabled());
        assertTrue(services.findById(betaService).orElseThrow().isEnabled());
        assertEquals(0, alertRules.count());
    }

    private HttpResponse<String> operation(String operation, UUID tenant, UUID service, String identity) throws Exception {
        return switch (operation) {
            case "organisation" -> call("GET", "/api/organisations/" + tenant, identity, null);
            case "list" -> call("GET", base(tenant), identity, null);
            case "read" -> call("GET", base(tenant) + "/" + service, identity, null);
            case "create" -> call("POST", base(tenant), identity, CREATE.formatted("Denied registration", "denied"));
            case "alert-read" -> call("GET", base(tenant) + "/" + service + "/alert-rule", identity, null);
            case "alert-write" -> call("PUT", base(tenant) + "/" + service + "/alert-rule", identity,
                    "{\"enabled\":true,\"consecutiveFailures\":3,\"consecutiveRecoveries\":2,\"severity\":\"HIGH\"}");
            default -> call("PATCH", base(tenant) + "/" + service + "/" + operation, identity, null);
        };
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpResponse<String> expect(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return response;
    }
    private static UUID id(HttpResponse<String> response) { return UUID.fromString(JSON.readTree(response.body()).get("id").asText()); }
    private static String base(UUID tenant) { return "/api/organisations/" + tenant + "/monitored-services"; }

    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String token(String subject, String issuer, String audience, KeyPair keys, int lifetime) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(Instant.now().plusSeconds(lifetime)))
                .claim("email", subject + "@example.test").claim("email_verified", true)
                .claim("realm_access", java.util.Map.of("roles", List.of("OWNER"))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("tenant-test").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }
    private static HttpServer jwks() {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(new RSAKey.Builder((RSAPublicKey) KEYS.getPublic()).keyID("tenant-test").build())
                    .toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
