package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import com.opspilot.backend.domain.HealthStatus;
import org.springframework.stereotype.Component;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class HttpHealthProbe {
    private final MonitoringProperties properties;
    private final HealthTargetPolicy policy;
    private final Clock clock;
    private final ScheduledExecutorService deadlines;

    public HttpHealthProbe(MonitoringProperties properties, HealthTargetPolicy policy, Clock clock,
                           ScheduledExecutorService healthCheckDeadlines) {
        this.properties = properties;
        this.policy = policy;
        this.clock = clock;
        this.deadlines = healthCheckDeadlines;
    }

    public HealthObservation check(HealthCheckTarget service) {
        Instant started = clock.instant();
        long startNanos = System.nanoTime();
        AtomicBoolean expired = new AtomicBoolean();
        try (Socket socket = new Socket()) {
            // Close the actual socket at the total deadline (including TLS/slow header reads).
            var deadline = deadlines.schedule(() -> {
                expired.set(true);
                try { socket.close(); } catch (IOException ignored) { }
            }, properties.requestTimeoutMs(), TimeUnit.MILLISECONDS);
            try {
                HealthTargetPolicy.Target target = policy.validate(service.baseUrl(), service.healthEndpoint());
                socket.connect(new InetSocketAddress(target.address(), target.port()), properties.connectTimeoutMs());
                socket.setSoTimeout(properties.requestTimeoutMs());
                if (target.uri().getScheme().equals("https")) {
                    // Preserve original TLS peer name/certificate validation while connecting to the pinned IP.
                    try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(socket, target.host(), target.port(), true)) {
                        SSLParameters parameters = tls.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        tls.setSSLParameters(parameters);
                        tls.setSoTimeout(properties.requestTimeoutMs());
                        tls.startHandshake();
                        return exchange(tls, target, started, startNanos);
                    }
                }
                return exchange(socket, target, started, startNanos);
            } finally { deadline.cancel(false); }
        } catch (HealthTargetPolicy.BlockedTargetException exception) {
            return observation(started, startNanos, HealthStatus.UNKNOWN, null, "Target blocked by outbound HTTP policy.");
        } catch (UnknownHostException exception) {
            return observation(started, startNanos, HealthStatus.DOWN, null, "DNS resolution failed.");
        } catch (SocketTimeoutException exception) {
            return observation(started, startNanos, HealthStatus.DOWN, null, "HTTP check timed out.");
        } catch (javax.net.ssl.SSLException exception) {
            return observation(started, startNanos, HealthStatus.DOWN, null,
                    expired.get() ? "HTTP check timed out." : "TLS connection failed.");
        } catch (IOException exception) {
            return observation(started, startNanos, HealthStatus.DOWN, null,
                    expired.get() ? "HTTP check timed out." : "HTTP connection failed or response was invalid.");
        }
    }

    private HealthObservation exchange(Socket socket, HealthTargetPolicy.Target target, Instant started, long nanos) throws IOException {
        URI uri = target.uri();
        String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        String request = "GET " + URI.create(path).toASCIIString() + " HTTP/1.1\r\nHost: " + uri.getRawAuthority()
                + "\r\nUser-Agent: OpsPilot-Health/1.0\r\nAccept: */*\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        // Status/header-only probe. Bound parsing and never read/store a response body or cookies.
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (headers.size() < 16384 && matched < terminator.length) {
            int next = input.read();
            if (next < 0) throw new IOException("Incomplete HTTP headers");
            headers.write(next);
            matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        if (matched != 4) throw new IOException("HTTP headers exceed limit");
        String statusLine = headers.toString(StandardCharsets.ISO_8859_1).split("\r\n", 2)[0];
        if (!statusLine.matches("HTTP/1\\.[01] [2-5][0-9]{2}( [^\\r\\n]*)?")) throw new IOException("Invalid final HTTP status");
        int code = Integer.parseInt(statusLine.substring(9, 12));
        return observation(started, nanos, code < 300 ? HealthStatus.UP : HealthStatus.DOWN, code,
                code < 300 ? null : "Unexpected HTTP status: " + code + ". Redirects are not followed.");
    }

    private HealthObservation observation(Instant started, long nanos, HealthStatus status, Integer code, String failure) {
        Instant completed = clock.instant();
        return new HealthObservation(started, completed.isBefore(started) ? started : completed, status, code,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos), failure);
    }
}
