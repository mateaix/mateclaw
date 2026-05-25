package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.mcp.runtime.McpClientManager.HttpEndpointConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link McpClientManager#splitHttpUrl(String, String)} produces
 * the {@code baseUrl} / {@code endpoint} pair the underlying SDK builders
 * expect, so a user-configured non-default path or query string is not
 * silently dropped.
 */
class McpClientManagerSplitHttpUrlTest {

    @Test
    @DisplayName("URL without path falls back to the transport's default endpoint")
    void hostOnlyUsesDefaultEndpoint() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://example.com", "/mcp");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("Bare slash path is treated as no path")
    void rootPathUsesDefaultEndpoint() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://example.com/", "/mcp");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("Standard /mcp suffix round-trips")
    void standardMcpSuffix() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://example.com/mcp", "/mcp");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("Non-standard nested path is preserved as endpoint")
    void nonStandardPathPreserved() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://api.example.com/api/v1/mcp", "/mcp");
        assertEquals("https://api.example.com", cfg.baseUrl());
        assertEquals("/api/v1/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("Query string is appended to the endpoint")
    void queryStringPreserved() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://example.com/mcp?token=abc", "/mcp");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/mcp?token=abc", cfg.endpoint());
    }

    @Test
    @DisplayName("Query string survives even when path is empty")
    void queryStringWithoutPath() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://example.com?token=abc", "/mcp");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/mcp?token=abc", cfg.endpoint());
    }

    @Test
    @DisplayName("Port and userinfo stay on the base URL")
    void hostWithPort() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("http://localhost:8080/api/mcp", "/mcp");
        assertEquals("http://localhost:8080", cfg.baseUrl());
        assertEquals("/api/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("IPv6 authority is preserved")
    void ipv6Host() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("http://[::1]:8080/mcp", "/mcp");
        assertEquals("http://[::1]:8080", cfg.baseUrl());
        assertEquals("/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("SSE default endpoint is honoured")
    void sseDefaultEndpoint() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("https://example.com", "/sse");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/sse", cfg.endpoint());
    }

    @Test
    @DisplayName("Whitespace around URL is trimmed")
    void trimsWhitespace() {
        HttpEndpointConfig cfg = McpClientManager.splitHttpUrl("  https://example.com/mcp  ", "/mcp");
        assertEquals("https://example.com", cfg.baseUrl());
        assertEquals("/mcp", cfg.endpoint());
    }

    @Test
    @DisplayName("Null URL is rejected")
    void nullUrlThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> McpClientManager.splitHttpUrl(null, "/mcp"));
    }

    @Test
    @DisplayName("Empty URL is rejected")
    void emptyUrlThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> McpClientManager.splitHttpUrl("   ", "/mcp"));
    }

    @Test
    @DisplayName("Missing scheme is rejected")
    void missingSchemeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> McpClientManager.splitHttpUrl("example.com/mcp", "/mcp"));
    }

    @Test
    @DisplayName("Malformed URL is rejected")
    void malformedUrlThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> McpClientManager.splitHttpUrl("http://exa mple.com/mcp", "/mcp"));
    }
}
