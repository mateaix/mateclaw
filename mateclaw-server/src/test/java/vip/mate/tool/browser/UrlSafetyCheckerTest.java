package vip.mate.tool.browser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlSafetyCheckerTest {

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
}
