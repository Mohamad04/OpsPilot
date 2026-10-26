package com.opspilot.backend.application;

import com.opspilot.backend.config.MonitoringProperties;
import org.springframework.stereotype.Component;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

@Component
public class HealthTargetPolicy {
    private final MonitoringProperties properties;

    public HealthTargetPolicy(MonitoringProperties properties) { this.properties = properties; }

    public record Target(URI uri, String host, int port, InetAddress address) { }
    public static class BlockedTargetException extends RuntimeException { }

    public Target validate(String baseUrl, String healthEndpoint) throws UnknownHostException {
        URI base;
        URI endpoint;
        try {
            base = URI.create(baseUrl);
            endpoint = URI.create(healthEndpoint);
        } catch (IllegalArgumentException exception) { throw new BlockedTargetException(); }
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        String host = base.getHost();
        if (!(scheme.equals("http") || scheme.equals("https")) || host == null
                || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || endpoint.isAbsolute() || endpoint.getRawAuthority() != null || endpoint.getRawFragment() != null
                || !healthEndpoint.startsWith("/") || healthEndpoint.startsWith("//")) throw new BlockedTargetException();
        host = host.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        if (host.contains("%") || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) throw new BlockedTargetException();
        int port = base.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : base.getPort();
        if (!properties.allowedPorts().contains(port)) throw new BlockedTargetException();
        String path = base.getRawPath().replaceAll("/+$", "") + endpoint.getRawPath();
        URI uri = URI.create(scheme + "://" + base.getRawAuthority() + path
                + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery()));
        if (uri.toASCIIString().length() > 8192) throw new BlockedTargetException();
        InetAddress[] addresses = resolve(host);
        if (addresses.length == 0) throw new UnknownHostException();
        for (InetAddress address : addresses) if (!isPublic(address)) throw new BlockedTargetException();
        // Connect ONLY to this already validated address. No second hostname lookup/fallback.
        return new Target(uri, host, port, addresses[0]);
    }

    protected InetAddress[] resolve(String host) throws UnknownHostException { return InetAddress.getAllByName(host); }

    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 168 && b == 63 && c == 129 && (bytes[3] & 255) == 16) // Azure platform virtual IP.
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113));
        }
        // Only global IPv6 unicast, excluding protocol assignments, documentation and 6to4.
        return bytes.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x01 && ((bytes[2] & 255) <= 1
                    || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x3f && b == 0xff && (bytes[2] & 0xf0) == 0);
    }
}
