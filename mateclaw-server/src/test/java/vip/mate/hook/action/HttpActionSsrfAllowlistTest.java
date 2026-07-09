package vip.mate.hook.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hook HTTP action requires the target host to be in {@code trustedDomains}
 * AND to not be a private/loopback address. An entry in the shared SSRF
 * allowlist lifts the private-address block for that specific host.
 */
class HttpActionSsrfAllowlistTest {

    private static HttpAction action(String host, List<String> trusted, List<String> ssrfAllowlist) {
        return new HttpAction(
                RestClient.builder().build(),
                "POST",
                URI.create("http://" + host + "/hook"),
                null,
                trusted,
                ssrfAllowlist,
                3000L,
                null,
                null);
    }

    @Test
    @DisplayName("Private host is rejected even when trusted, without an allowlist entry")
    void privateHostRejectedWithoutAllowlist() {
        HttpAction a = action("192.168.100.100", List.of("192.168.100.100"), List.of());
        assertThrows(IllegalArgumentException.class, a::validate);
    }

    @Test
    @DisplayName("Allowlisting the private host (with trust) lets validate() pass")
    void privateHostAllowedWithAllowlist() {
        HttpAction a = action("192.168.100.100", List.of("192.168.100.100"), List.of("192.168.100.0/24"));
        assertDoesNotThrow(a::validate);
    }

    @Test
    @DisplayName("Allowlist does not bypass the trusted-domains requirement")
    void allowlistDoesNotBypassTrust() {
        HttpAction a = action("192.168.100.100", List.of(), List.of("192.168.100.100"));
        assertThrows(IllegalArgumentException.class, a::validate);
    }
}
