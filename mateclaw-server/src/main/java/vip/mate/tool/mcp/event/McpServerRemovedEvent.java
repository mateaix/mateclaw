package vip.mate.tool.mcp.event;

/**
 * Fires after an MCP server row has been deleted from {@code mate_mcp_server}.
 *
 * <p>Downstream listeners scrub records that reference the server's tools — most
 * importantly the agent-tool binding rows in {@code mate_agent_tool}, whose tool
 * names are {@code mcp_<serverId>_<slug>_<hash6>}. Without this cleanup, deleting
 * an MCP server leaves orphan bindings the agent edit page still shows and the
 * user can no longer clear (the tool no longer exists in the live set, so the
 * picker can't render a row to uncheck).
 *
 * <p>Distinct from {@link McpServerChangedEvent}, which signals a connection-state
 * change (the agent cache is rebuilt) but does not imply the server row is gone.
 *
 * @param serverId   DB id of the removed {@code mate_mcp_server} row
 * @param serverName name the row carried, for log lines
 */
public record McpServerRemovedEvent(Long serverId, String serverName) {
}
