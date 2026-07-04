package vip.mate.tool.browser;

import vip.mate.common.net.SsrfAllowlist;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * SSRF guard — rejects URLs that resolve to loopback, link-local, private, or
 * known cloud-metadata endpoints.
 *
 * <p>Call this before passing any user-controlled URL to the browser or to an
 * outbound HTTP client. An optional allowlist lets administrators reach specific
 * internal hosts/IPs/CIDR blocks while every other restricted address stays blocked.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>Strict</b> (default, {@code allowPrivateNetwork=false}) — blocks
 *       loopback, any-local, link-local, site-local (private), multicast, and
 *       all known cloud-metadata endpoints. Use when the agent may reach the
 *       public internet, where SSRF protection is required.</li>
 *   <li><b>Private-network allow</b> ({@code allowPrivateNetwork=true}) — for
 *       isolated LAN / on-prem deployments. Loopback, private, and link-local
 *       addresses are allowed through; only cloud-metadata endpoints remain
 *       blocked so a misconfigured agent cannot exfiltrate cloud credentials.</li>
 * </ul>
 *
 * <p>Known limitation: DNS rebinding (TOCTOU). The check resolves the hostname
 * once and validates every returned address, but the browser may re-resolve
 * the same hostname later and obtain a different IP. Fully closing this requires
 * hooking the browser's DNS layer, which Playwright does not expose; the
 * private-network-allow mode makes this a non-issue because every private
 * address is permitted anyway.
 */
public final class UrlSafetyChecker {

    /** Hostnames that must never be reachable via user-supplied URLs. */
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "ip6-localhost",
            "metadata.google.internal",
            "metadata.aws.internal",
            "instance-data",
            "169.254.169.254",     // AWS / Azure / GCP IMDS
            "100.100.100.200",     // Alibaba Cloud IMDS
            "192.0.0.192",         // Oracle Cloud IMDS
            "0.0.0.0",
            "::1"
    );

    /**
     * Subset of {@link #BLOCKED_HOSTNAMES} that are cloud instance-metadata endpoints.
     * These are blocked unconditionally — even an explicit allowlist entry must never
     * open a path to instance-metadata credential theft.
     */
    private static final Set<String> METADATA_HOSTNAMES = Set.of(
            "metadata.google.internal",
            "metadata.aws.internal",
            "instance-data",
            "169.254.169.254",
            "100.100.100.200",
            "192.0.0.192"
    );

    private UrlSafetyChecker() {}

    /**
     * Throw {@link SecurityException} if the URL is unsafe. Accepts http:// and https:// only.
     * Equivalent to {@code check(url, List.of(), false)} (strict mode, no allowlist).
     */
    public static void check(String url) {
        check(url, List.of(), false);
    }

    /**
     * Throw {@link SecurityException} if the URL is unsafe (strict mode).
     *
     * @param allowlist hostnames, literal IPs, or IPv4 CIDR blocks that are permitted even
     *                  when they would otherwise be blocked (loopback, private, metadata, …).
     */
    public static void check(String url, Collection<String> allowlist) {
        check(url, allowlist, false);
    }

    /**
     * Throw {@link SecurityException} if the URL is unsafe (no allowlist).
     *
     * @param allowPrivateNetwork {@code true} permits loopback / private / link-local
     *                            addresses; cloud-metadata endpoints stay blocked.
     */
    public static void check(String url, boolean allowPrivateNetwork) {
        check(url, List.of(), allowPrivateNetwork);
    }

    /**
     * Throw {@link SecurityException} if the URL is unsafe.
     *
     * @param allowlist           hostnames, literal IPs, or IPv4 CIDR blocks that are
     *                            permitted even when they would otherwise be blocked.
     * @param allowPrivateNetwork {@code true} permits loopback / private / link-local
     *                            addresses; cloud-metadata endpoints stay blocked.
     *                            Use only for isolated LAN / on-prem deployments where
     *                            the agent has no path to the public internet.
     */
    public static void check(String url, Collection<String> allowlist, boolean allowPrivateNetwork) {
        if (url == null || url.isBlank()) {
            throw new SecurityException("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SecurityException("Only http:// and https:// URLs are allowed (got: " + scheme + ")");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("URL must have a host");
        }
        String hostname = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        String lowerHost = hostname.toLowerCase();
        // Cloud-metadata endpoints are blocked unconditionally — an allowlist entry
        // must never open a path to instance-metadata credential theft.
        if (METADATA_HOSTNAMES.contains(lowerHost)) {
            throw new SecurityException("SSRF blocked: " + hostname + " is a cloud-metadata endpoint");
        }
        // An explicit allowlist entry for the literal host bypasses the loopback /
        // private / restricted-hostname checks below — but never the metadata checks.
        boolean hostAllowlisted = SsrfAllowlist.matchesHost(hostname, allowlist);
        if (!hostAllowlisted && BLOCKED_HOSTNAMES.contains(lowerHost)) {
            throw new SecurityException("SSRF blocked: " + hostname + " is a restricted hostname");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(hostname)) {
                // Cloud-metadata IPs are blocked in every mode and regardless of the
                // allowlist — never exfiltrate cloud credentials via the browser tool.
                if (isMetadataIp(addr)) {
                    throw new SecurityException("SSRF blocked: " + hostname
                            + " resolves to cloud-metadata endpoint " + addr.getHostAddress());
                }
                if (hostAllowlisted || SsrfAllowlist.matchesAddress(addr, allowlist)) {
                    continue;
                }
                if (allowPrivateNetwork) {
                    // Skip loopback / any-local / link-local / site-local / multicast checks.
                    continue;
                }
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new SecurityException("SSRF blocked: " + hostname
                            + " resolves to restricted address " + addr.getHostAddress());
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // DNS resolution failure — let the caller deal with it (browser will show its own error).
            // Known limitation: a deliberately slow/timeout DNS server can use this to bypass the
            // guard. Not fixable here without a hard fail policy; document as accepted risk.
        }
    }

    /**
     * Identify cloud instance-metadata endpoints by IP. Covers IPv4 literals used by
     * AWS / Azure / GCP / Alibaba, and the AWS IPv6 IMDS prefix {@code fd00:ec2::/64}
     * (the only documented IPv6 metadata range). IPv6 addresses outside this prefix
     * but inside the broader ULA range {@code fc00::/7} are NOT treated as metadata.
     */
    private static boolean isMetadataIp(InetAddress addr) {
        String ip = addr.getHostAddress();
        // IPv4 literals — kept as string compares for clarity and zero allocation on the hot path.
        if ("169.254.169.254".equals(ip)
                || "100.100.100.200".equals(ip)
                || "192.0.0.192".equals(ip)) {
            return true;
        }
        // AWS IPv6 IMDS lives in fd00:ec2::/64 — match by 64-bit prefix to cover
        // fd00:ec2::254 and any future variant under the same prefix.
        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            // 16 bytes; first 8 must equal fd 00 0e c2 00 00 00 00
            return b.length == 16
                    && b[0] == (byte) 0xfd && b[1] == 0x00
                    && b[2] == 0x0e && b[3] == (byte) 0xc2
                    && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0;
        }
        return false;
    }
}
