package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HealthTargetPolicyTest {
    static final MonitoringProperties SETTINGS = new MonitoringProperties(60000, 0, 200, 500, 2, List.of(80, 443));

    private HealthTargetPolicy policy(String... addresses) {
        return new HealthTargetPolicy(SETTINGS) {
            @Override protected InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] result = new InetAddress[addresses.length];
                for (int i = 0; i < addresses.length; i++) result[i] = InetAddress.getByName(addresses[i]);
                return result;
            }
        };
    }

    @Test void joinsPathsWithoutLosingBasePathAndPinsValidatedAddress() throws Exception {
        var target = policy("8.8.8.8", "2606:4700:4700::1111").validate("https://status.example.test/api/", "/health?full=true");
        assertEquals("https://status.example.test/api/health?full=true", target.uri().toASCIIString());
        assertEquals("status.example.test", target.host());
        assertEquals(443, target.port());
        assertEquals(InetAddress.getByName("8.8.8.8"), target.address());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.test", "http://user:secret@example.test",
            "http://example.test?token=secret", "https://example.test/#fragment", "http://localhost",
            "http://foo.localhost", "http://metadata.google.internal", "http://host.local", "http://example.test:22",
            "http://example.test:0", "http://example.test:65536", "http://example.test\\@localhost", "http://[fe80::1%25eth0]"})
    void unsafeUrlsAreRejectedBeforeNetworkAccess(String base) {
        assertThrows(HealthTargetPolicy.BlockedTargetException.class, () -> policy("8.8.8.8").validate(base, "/health"));
    }

    @ParameterizedTest @ValueSource(strings = {"//internal/health", "https://other.test/health", "health", "", "/health#secret", "/health\r\nHost: localhost"})
    void endpointsCannotReplaceAuthorityOrInjectHeaders(String endpoint) {
        assertThrows(HealthTargetPolicy.BlockedTargetException.class, () -> policy("8.8.8.8").validate("https://example.test", endpoint));
    }

    @ParameterizedTest @ValueSource(strings = {"0.0.0.0", "0.1.2.3", "10.1.2.3", "127.0.0.1", "127.1",
            "2130706433", "100.64.1.2", "168.63.129.16", "169.254.169.254", "172.16.0.1", "192.168.1.1", "192.0.0.8",
            "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.1.2.3",
            "255.255.255.255", "::", "::1", "::ffff:127.0.0.1", "fd00::1", "fe80::1", "ff02::1", "64:ff9b::a00:1",
            "2001:db8::1", "2001::1", "2002:7f00:1::1", "3fff::1"})
    void privateSpecialAndTransitionAddressesAreBlocked(String address) {
        assertThrows(HealthTargetPolicy.BlockedTargetException.class, () -> policy(address).validate("http://example.test", "/health"));
    }

    @Test void mixedDnsAnswersFailClosed() {
        assertThrows(HealthTargetPolicy.BlockedTargetException.class,
                () -> policy("8.8.8.8", "127.0.0.1").validate("http://example.test", "/health"));
    }

    @Test void allAddressesAreValidatedUsingOneResolution() throws Exception {
        int[] lookups = {0};
        var policy = new HealthTargetPolicy(SETTINGS) {
            @Override protected InetAddress[] resolve(String host) throws UnknownHostException {
                lookups[0]++;
                return new InetAddress[]{InetAddress.getByName(lookups[0] == 1 ? "8.8.8.8" : "127.0.0.1")};
            }
        };
        assertEquals("8.8.8.8", policy.validate("http://rebind.example.test", "/health").address().getHostAddress());
        assertEquals(1, lookups[0]);
    }

    @Test void noDnsAnswerIsAResolutionFailure() {
        assertThrows(UnknownHostException.class, () -> policy().validate("https://missing.example.test", "/health"));
    }
}
