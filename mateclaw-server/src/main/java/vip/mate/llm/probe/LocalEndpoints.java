package vip.mate.llm.probe;

import java.net.InetAddress;
import java.net.URI;

/**
 * Heuristics for deciding whether a base URL points at a locally hosted /
 * self-hosted inference server. Probing is restricted to such endpoints so no
 * probe traffic ever reaches a cloud provider.
 */
final class LocalEndpoints {

    private LocalEndpoints() {
    }

    /**
     * @return true when the URL's host is loopback, a private / link-local
     *         IPv4 range, an mDNS {@code .local} name, or a well-known
     *         container-host alias.
     */
    static boolean isLocal(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".local")
                || lower.equals("host.docker.internal") || lower.equals("host.containers.internal")) {
            return true;
        }
        // Literal IP addresses only — never resolve DNS here: a probe gate
        // must not add name-resolution latency or leak lookups for cloud hosts.
        byte[] addr = parseLiteralAddress(lower);
        if (addr == null) {
            return false;
        }
        try {
            InetAddress inet = InetAddress.getByAddress(addr);
            return inet.isLoopbackAddress() || inet.isSiteLocalAddress() || inet.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse an IPv4/IPv6 literal without triggering DNS. Returns null for hostnames. */
    private static byte[] parseLiteralAddress(String host) {
        String h = host;
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.contains(":")) {
            // IPv6 literal — only loopback matters in practice for local servers.
            try {
                return InetAddress.getByName(h).getAddress();
            } catch (Exception e) {
                return null;
            }
        }
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
