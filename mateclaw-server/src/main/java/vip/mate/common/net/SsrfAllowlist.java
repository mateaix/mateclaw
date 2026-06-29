package vip.mate.common.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collection;

/**
 * Shared matching logic for the outbound-request SSRF allowlist.
 *
 * <p>Outbound HTTP guards (browser navigation, hook webhooks, image download)
 * block loopback, private, link-local and cloud-metadata targets by default.
 * Administrators can punch a narrow hole for a specific internal host via the
 * allowlist; each entry is one of:
 * <ul>
 *   <li>a literal hostname — {@code internal.corp}</li>
 *   <li>a literal IP — {@code 192.168.100.100}</li>
 *   <li>an IPv4 CIDR block — {@code 192.168.100.0/24}</li>
 * </ul>
 *
 * <p>{@link #matchesHost} compares against the URL host string as written (no
 * DNS lookup); {@link #matchesAddress} compares against an already-resolved
 * address. A guard that resolves DNS should consult both so that neither the
 * literal host nor any resolved address is missed.
 */
public final class SsrfAllowlist {

    private SsrfAllowlist() {}

    /** True when the literal host string (hostname or IP literal) matches an allowlist entry. */
    public static boolean matchesHost(String host, Collection<String> allowlist) {
        if (host == null || host.isBlank() || allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        String h = stripBrackets(host.trim());
        Integer hostIp = ipv4ToInt(h); // non-null only when h is an IPv4 literal
        for (String raw : allowlist) {
            String entry = trimOrNull(raw);
            if (entry == null) {
                continue;
            }
            if (entry.indexOf('/') >= 0) {
                if (hostIp != null && ipv4InCidr(hostIp, entry)) {
                    return true;
                }
            } else if (entry.equalsIgnoreCase(h)) {
                return true;
            }
        }
        return false;
    }

    /** True when a resolved address matches an allowlist entry (literal IP or IPv4 CIDR). */
    public static boolean matchesAddress(InetAddress addr, Collection<String> allowlist) {
        if (addr == null || allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        String ip = addr.getHostAddress();
        Integer addrIp = (addr instanceof Inet4Address) ? bytesToInt(addr.getAddress()) : null;
        for (String raw : allowlist) {
            String entry = trimOrNull(raw);
            if (entry == null) {
                continue;
            }
            if (entry.indexOf('/') >= 0) {
                if (addrIp != null && ipv4InCidr(addrIp, entry)) {
                    return true;
                }
            } else if (entry.equalsIgnoreCase(ip)) {
                return true;
            }
        }
        return false;
    }

    private static String trimOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    /** Membership test for an IPv4 address (as a 32-bit int) against a {@code a.b.c.d/prefix} block. */
    private static boolean ipv4InCidr(int addrBits, String cidr) {
        int slash = cidr.indexOf('/');
        Integer networkBits = ipv4ToInt(cidr.substring(0, slash).trim());
        if (networkBits == null) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        int mask = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix);
        return (addrBits & mask) == (networkBits & mask);
    }

    /** Parse a dotted-quad IPv4 literal into a 32-bit int, or null if it is not one. */
    private static Integer ipv4ToInt(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int result = 0;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    private static int bytesToInt(byte[] bytes) {
        int result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }
}
