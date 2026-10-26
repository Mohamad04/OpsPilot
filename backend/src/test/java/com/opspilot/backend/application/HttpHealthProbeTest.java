package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import com.opspilot.backend.domain.HealthStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpHealthProbeTest {
    private HttpServer server;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final HealthTargetPolicy policy = mock(HealthTargetPolicy.class);
    private final MonitoringProperties settings = new MonitoringProperties(60000, 0, 150, 500, 2, List.of(80, 443));
    private HttpHealthProbe probe;
    private final HealthCheckTarget service = new HealthCheckTarget(UUID.randomUUID(), UUID.randomUUID(), "http://never-resolve.example.test/api", "/health");

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.start();
        when(policy.validate(service.baseUrl(), service.healthEndpoint())).thenReturn(target("http"));
        probe = new HttpHealthProbe(settings, policy, Clock.systemUTC(), deadlines);
    }
    @AfterEach void cleanup() { server.stop(0); handlers.shutdownNow(); deadlines.shutdownNow(); }
    private HealthTargetPolicy.Target target(String scheme) throws Exception {
        return new HealthTargetPolicy.Target(URI.create(scheme + "://never-resolve.example.test:" + server.getAddress().getPort() + "/api/health"),
                "never-resolve.example.test", server.getAddress().getPort(), InetAddress.getByName("127.0.0.1"));
    }

    @ParameterizedTest @ValueSource(ints = {200, 204, 299, 301, 400, 401, 404, 500, 503})
    void recordsActualStatusAndUsesPinnedAddressWithOriginalHost(int status) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/health", exchange -> {
            hits.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("never-resolve.example.test:" + server.getAddress().getPort(), exchange.getRequestHeaders().getFirst("Host"));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1/private");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        HealthObservation result = probe.check(service);
        assertEquals(status < 300 ? HealthStatus.UP : HealthStatus.DOWN, result.status());
        assertEquals(status, result.httpStatusCode());
        assertTrue(result.responseTimeMs() >= 0);
        assertFalse(result.completedAt().isBefore(result.startedAt()));
        assertEquals(1, hits.get()); // In particular, redirects never send another request.
        verify(policy).validate(service.baseUrl(), service.healthEndpoint());
    }

    @Test void slowHeadersHitTheTotalDeadlineWithoutWaitingForTheServer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/api/health", exchange -> {
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try {
            HealthObservation result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> probe.check(service));
            assertEquals(HealthStatus.DOWN, result.status());
            assertNull(result.httpStatusCode());
            assertEquals("HTTP check timed out.", result.failureDetail());
        } finally { release.countDown(); }
    }

    @Test void dnsFailureDoesNotExposeExceptionDetails() throws Exception {
        when(policy.validate(anyString(), anyString())).thenThrow(new UnknownHostException("secret-internal-host"));
        var result = probe.check(service);
        assertEquals(HealthStatus.DOWN, result.status());
        assertEquals("DNS resolution failed.", result.failureDetail());
        assertNull(result.httpStatusCode());
    }

    @Test void blockedTargetIsUnknownWithoutContactingTheServer() throws Exception {
        when(policy.validate(anyString(), anyString())).thenThrow(new HealthTargetPolicy.BlockedTargetException());
        assertEquals(HealthStatus.UNKNOWN, probe.check(service).status());
    }

    @Test void refusedConnectionIsDown() throws Exception {
        server.stop(0);
        var result = probe.check(service);
        assertEquals(HealthStatus.DOWN, result.status());
        assertNull(result.httpStatusCode());
        assertNotNull(result.failureDetail());
    }

    @ParameterizedTest @ValueSource(strings = {"not-http\r\n\r\n", "HTTP/1.1 999 Invalid\r\n\r\n", "HTTP/1.1 200 OK\r\nX: incomplete", "large"})
    void malformedIncompleteOrOversizedHeadersAreDown(String response) throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            when(policy.validate(anyString(), anyString())).thenReturn(new HealthTargetPolicy.Target(
                    URI.create("http://fixture.test/health"), "fixture.test", raw.getLocalPort(), InetAddress.getByName("127.0.0.1")));
            Future<?> handler = handlers.submit(() -> {
                try (var socket = raw.accept()) {
                    String body = response.equals("large") ? "HTTP/1.1 200 OK\r\nX: " + "a".repeat(17000) + "\r\n\r\n" : response;
                    socket.getOutputStream().write(body.getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) { }
            });
            assertEquals(HealthStatus.DOWN, probe.check(service).status());
            handler.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void plainHttpOnATlsPortIsNotAcceptedAsHealthy() throws Exception {
        when(policy.validate(anyString(), anyString())).thenReturn(target("https"));
        var result = probe.check(service);
        assertEquals(HealthStatus.DOWN, result.status());
        assertNull(result.httpStatusCode());
    }
}
