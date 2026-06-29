package vip.mate.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfAllowlistTest {

    @Test
    @DisplayName("matchesHost: exact IP and hostname, case-insensitive")
    void matchesHostExact() {
        assertTrue(SsrfAllowlist.matchesHost("192.168.100.100", List.of("192.168.100.100")));
        assertTrue(SsrfAllowlist.matchesHost("Internal.Corp", List.of("internal.corp")));
        assertFalse(SsrfAllowlist.matchesHost("192.168.100.101", List.of("192.168.100.100")));
        assertFalse(SsrfAllowlist.matchesHost("evil.com", List.of("internal.corp")));
    }

    @Test
    @DisplayName("matchesHost: IPv4 CIDR matches contained IP literals only")
    void matchesHostCidr() {
        assertTrue(SsrfAllowlist.matchesHost("192.168.100.1", List.of("192.168.100.0/24")));
        assertTrue(SsrfAllowlist.matchesHost("192.168.100.254", List.of("192.168.100.0/24")));
        assertFalse(SsrfAllowlist.matchesHost("192.168.101.1", List.of("192.168.100.0/24")));
        // A hostname is not an IP, so it never matches a CIDR entry.
        assertFalse(SsrfAllowlist.matchesHost("internal.corp", List.of("192.168.100.0/24")));
    }

    @Test
    @DisplayName("matchesHost: bracketed IPv6 literal compares stripped form")
    void matchesHostIpv6Brackets() {
        assertTrue(SsrfAllowlist.matchesHost("[fd00::1]", List.of("fd00::1")));
    }

    @Test
    @DisplayName("matchesAddress: exact IP and CIDR against resolved address")
    void matchesAddressIpv4() throws Exception {
        InetAddress addr = InetAddress.getByName("192.168.100.100");
        assertTrue(SsrfAllowlist.matchesAddress(addr, List.of("192.168.100.100")));
        assertTrue(SsrfAllowlist.matchesAddress(addr, List.of("192.168.100.0/24")));
        assertFalse(SsrfAllowlist.matchesAddress(addr, List.of("10.0.0.0/8")));
    }

    @Test
    @DisplayName("Empty / null allowlist never matches; whitespace and bad entries are ignored")
    void emptyAndMalformed() {
        assertFalse(SsrfAllowlist.matchesHost("192.168.1.1", List.of()));
        assertFalse(SsrfAllowlist.matchesHost("192.168.1.1", null));
        assertFalse(SsrfAllowlist.matchesHost("192.168.1.1", List.of("  ", "not-a-cidr/99", "999.1.1.1")));
        assertTrue(SsrfAllowlist.matchesHost("192.168.1.1", List.of("  ", " 192.168.1.1 ")));
    }
}
