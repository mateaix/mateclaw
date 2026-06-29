package vip.mate.agent.binding.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP binding-cleanup prefix match must be exact: deleting server 123's
 * bindings must not also delete server 1234's, which a naive SQL
 * {@code LIKE 'mcp_123_%'} would (the underscores are wildcards).
 */
class AgentBindingMcpRemovalListenerTest {

    private static String prefix(long serverId) {
        return McpToolNameResolver.PREFIX + serverId + "_";
    }

    @Test
    @DisplayName("Matches only the exact server's tools")
    void matchesExactServer() {
        String p = prefix(123L);
        assertTrue(AgentBindingMcpRemovalListener.belongsToServer("mcp_123_ping_ab12cd", p));
        assertTrue(AgentBindingMcpRemovalListener.belongsToServer("mcp_123_search_99ffaa", p));
    }

    @Test
    @DisplayName("Does NOT match a sibling server whose id shares the prefix digits")
    void doesNotMatchSiblingServer() {
        String p = prefix(123L);
        // The LIKE-wildcard trap: 'mcp_123_%' would match these, startsWith must not.
        assertFalse(AgentBindingMcpRemovalListener.belongsToServer("mcp_1234_ping_ab12cd", p));
        assertFalse(AgentBindingMcpRemovalListener.belongsToServer("mcp_12_ping_ab12cd", p));
        assertFalse(AgentBindingMcpRemovalListener.belongsToServer("mcp_1230_x_y", p));
    }

    @Test
    @DisplayName("Non-MCP and malformed names never match")
    void ignoresNonMcp() {
        String p = prefix(123L);
        assertFalse(AgentBindingMcpRemovalListener.belongsToServer("web_search", p));
        assertFalse(AgentBindingMcpRemovalListener.belongsToServer("mcp_123", p)); // no trailing separator
        assertFalse(AgentBindingMcpRemovalListener.belongsToServer(null, p));
    }
}
