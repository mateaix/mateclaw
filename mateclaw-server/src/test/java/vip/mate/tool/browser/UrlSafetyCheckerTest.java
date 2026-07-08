package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link UrlSafetyChecker}.
 *
 * <p>Covers:
 * <ul>
 *   <li>The three check overloads ({@code check(url)}, {@code check(url, allowlist)},
 *       {@code check(url, boolean)}, {@code check(url, allowlist, boolean)}).</li>
 *   <li>Strict mode (default) — blocks loopback / private / link-local / multicast / metadata.</li>
 *   <li>Private-network-allow mode — permits loopback / private / link-local but still
 *       blocks cloud-metadata endpoints (IPv4 literals and the AWS IPv6 IMDS prefix
 *       {@code fd00:ec2::/64}).</li>
 *   <li>Allowlist short-circuit, scheme/host validation, IPv6 AWS IMDS prefix matching.</li>
 * </ul>
 */
class UrlSafetyCheckerTest {

    // ==================== Strict mode (default) ====================

    @Test
    @DisplayName("Rejects private, loopback and metadata addresses by default")
    void blocksRestrictedAddressesWithoutAllowlist() {
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://192.168.100.100/admin"));
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://10.0.0.5/"));
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://127.0.0.1:8080/"));
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://localhost/"));
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("Rejects non-http schemes")
    void blocksNonHttpSchemes() {
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("file:///etc/passwd"));
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("ftp://192.168.1.1/"));
    }

    @Test
    @DisplayName("Allowlisting a literal private IP lets it through")
    void allowlistExactIp() {
        assertDoesNotThrow(() ->
                UrlSafetyChecker.check("http://192.168.100.100/admin", List.of("192.168.100.100")));
    }

    @Test
    @DisplayName("Allowlisting a CIDR block lets matching private IPs through")
    void allowlistCidr() {
        assertDoesNotThrow(() ->
                UrlSafetyChecker.check("http://192.168.100.100/x", List.of("192.168.100.0/24")));
        assertDoesNotThrow(() ->
                UrlSafetyChecker.check("http://192.168.100.250/y", List.of("192.168.100.0/24")));
    }

    @Test
    @DisplayName("An allowlist entry does not open up addresses outside it")
    void allowlistIsNarrow() {
        // 192.168.100.0/24 must not unblock a different private subnet.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://192.168.200.5/", List.of("192.168.100.0/24")));
        // Exact-IP allowlist must not unblock a sibling host.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://192.168.100.101/", List.of("192.168.100.100")));
    }

    @Test
    @DisplayName("Public addresses are always allowed")
    void allowsPublicAddresses() {
        assertDoesNotThrow(() -> UrlSafetyChecker.check("http://8.8.8.8/"));
        assertDoesNotThrow(() -> UrlSafetyChecker.check("https://1.1.1.1/"));
    }

    // ==================== AWS IPv6 IMDS prefix (new) ====================

    @Test
    @DisplayName("Blocks AWS IPv6 IMDS literal fd00:ec2::254 in strict mode")
    void blocksAwsIpv6ImdsLiteral() {
        // Before the prefix match, only the exact string "fd00:ec2::254" was blocked.
        // Now any address in fd00:ec2::/64 is blocked — this test guards the literal too.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://[fd00:ec2::254]/latest/meta-data/"));
    }

    @Test
    @DisplayName("Blocks any address in AWS IPv6 IMDS prefix fd00:ec2::/64 (strict mode)")
    void blocksAwsIpv6ImdsPrefix() {
        // These were NOT blocked before the prefix match was added — they would slip through
        // because InetAddress.isSiteLocalAddress() returns false for IPv6. The new
        // isMetadataIp byte-prefix check closes that gap.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://[fd00:ec2::1]/"));
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://[fd00:ec2::ffff]/"));
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://[fd00:ec2:0:0:0:0:0:1]/"));
    }

    // ==================== Private-network-allow mode (new) ====================

    @Nested
    @DisplayName("Private-network-allow mode (allowPrivateNetwork=true)")
    class PrivateNetworkAllowMode {

        @Test
        @DisplayName("Permits loopback IPv4")
        void permitsLoopbackIpv4() {
            assertDoesNotThrow(() ->
                    UrlSafetyChecker.check("http://127.0.0.1:18080/", true));
        }

        @Test
        @DisplayName("Permits private IPv4 ranges (10.x / 172.16-31.x / 192.168.x)")
        void permitsPrivateIpv4Ranges() {
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://10.0.0.5/", true));
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://192.168.1.1/", true));
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://172.16.0.1/", true));
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://172.31.255.255/", true));
        }

        @Test
        @DisplayName("Permits link-local IPv4 (169.254.0.0/16) except cloud metadata")
        void permitsLinkLocalIpv4() {
            // 169.254.x.x is link-local — typically used for LAN service discovery.
            // The metadata endpoint 169.254.169.254 is excluded separately below.
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://169.254.1.1/", true));
        }

        @Test
        @DisplayName("Still blocks cloud metadata IPv4 endpoints")
        void stillBlocksMetadataIpv4() {
            // Cloud metadata endpoints must remain blocked in every mode.
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://169.254.169.254/latest/meta-data/", true));
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://100.100.100.200/", true));
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://192.0.0.192/", true));
        }

        @Test
        @DisplayName("Still blocks AWS IPv6 IMDS endpoints (literal and prefix)")
        void stillBlocksAwsIpv6Imds() {
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://[fd00:ec2::254]/", true));
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://[fd00:ec2::1]/", true));
        }

        @Test
        @DisplayName("Still blocks hard-coded blocked hostnames (localhost, ::1)")
        void stillBlocksHardcodedHostnames() {
            // BLOCKED_HOSTNAMES is a hard blacklist that allowPrivateNetwork cannot bypass.
            // Operators who need these hosts must use the explicit allowlist instead.
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://localhost/", true));
            assertThrows(SecurityException.class, () ->
                    UrlSafetyChecker.check("http://[::1]/", true));
        }

        @Test
        @DisplayName("Permits public IPv4 addresses (no regression)")
        void permitsPublicIpv4() {
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://8.8.8.8/", true));
            assertDoesNotThrow(() -> UrlSafetyChecker.check("https://1.1.1.1/", true));
        }

        @Test
        @DisplayName("Three-arg overload combines allowlist + allowPrivateNetwork")
        void combinesAllowlistAndAllowPrivateNetwork() {
            // Allowlist short-circuits even in strict mode — but here allowPrivateNetwork=true
            // already permits the private IP, so the allowlist is redundant. Verify both
            // paths reach the same outcome.
            assertDoesNotThrow(() ->
                    UrlSafetyChecker.check("http://192.168.100.100/", List.of(), true));
            // Allowlist can still unblock a blocked hostname in private-network mode.
            assertDoesNotThrow(() ->
                    UrlSafetyChecker.check("http://localhost/", List.of("localhost"), true));
        }

        @Test
        @DisplayName("Boolean overload equals three-arg overload with empty allowlist")
        void booleanOverloadMatchesThreeArg() {
            // Sanity: check(url, true) behaves identically to check(url, List.of(), true).
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://127.0.0.1/", true));
            assertDoesNotThrow(() -> UrlSafetyChecker.check("http://127.0.0.1/", List.of(), true));
            assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://169.254.169.254/", true));
            assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://169.254.169.254/", List.of(), true));
        }
    }

    // ==================== Input validation ====================

    @Test
    @DisplayName("Rejects null or blank URL")
    void rejectsNullOrlBlankUrl() {
        SecurityException nullEx = assertThrows(SecurityException.class, () -> UrlSafetyChecker.check(null));
        assertEquals("URL is required", nullEx.getMessage());
        SecurityException blankEx = assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("   "));
        assertEquals("URL is required", blankEx.getMessage());
    }

    @Test
    @DisplayName("Rejects malformed URL")
    void rejectsMalformedUrl() {
        // URI.create rejects strings that are not valid URIs.
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://[invalid"));
    }

    @Test
    @DisplayName("Rejects URL without a host")
    void rejectsUrlWithoutHost() {
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("http://"));
        assertThrows(SecurityException.class, () -> UrlSafetyChecker.check("https:///path"));
    }

    @Test
    @DisplayName("Rejects blocked hostname metadata.google.internal")
    void blocksMetadataHostname() {
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://metadata.google.internal/computeMetadata/v1/"));
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://metadata.google.internal/", true));
    }

    @Test
    @DisplayName("An allowlist entry can never open a cloud-metadata endpoint")
    void allowlistCannotOverrideMetadata() {
        // Exact-IP allowlist of the metadata address must not let it through.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://169.254.169.254/latest/meta-data/",
                        List.of("169.254.169.254")));
        // A CIDR that covers the metadata IP must not let it through either.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://169.254.169.254/", List.of("169.254.0.0/16")));
        // Allowlisting the metadata hostname must not bypass the block.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://metadata.google.internal/",
                        List.of("metadata.google.internal")));
        // Even with private-network mode enabled AND an allowlist entry, metadata stays blocked.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://169.254.169.254/", List.of("169.254.0.0/16"), true));
        // Alibaba and Oracle metadata IPs are equally non-overridable.
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://100.100.100.200/", List.of("100.100.100.200"), true));
        assertThrows(SecurityException.class, () ->
                UrlSafetyChecker.check("http://192.0.0.192/", List.of("192.0.0.0/24"), true));
    }

    @Test
    @DisplayName("Allowlisting a non-metadata private host still works after the metadata-first reorder")
    void allowlistStillWorksForNonMetadata() {
        // Regression guard: making metadata unconditional must not break legitimate
        // allowlisting of ordinary private hosts.
        assertDoesNotThrow(() ->
                UrlSafetyChecker.check("http://192.168.50.10/", List.of("192.168.50.0/24")));
    }
}
