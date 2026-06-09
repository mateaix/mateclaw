package vip.mate.tool.mcp.event;

/**
 * Published whenever an MCP server's <em>connection state</em> changes —
 * connected, disconnected, reconnected, removed, or a (re)connect attempt
 * failed. The set of tools available to agents shifts on every one of these
 * transitions.
 *
 * <p>{@code AgentService} listens for this event and clears its agent-instance
 * cache, so the next chat turn rebuilds the agent graph against the current
 * live MCP tool set. Without this, an agent built before a server connected
 * (or while it was down) keeps a stale, tool-less snapshot until the process
 * restarts — see issue #289.
 *
 * <p>Mirrors the existing {@code ModelConfigChangedEvent} /
 * {@code ToolGuardConfigChangedEvent} pattern: the publisher (McpServerService)
 * depends only on {@code ApplicationEventPublisher}, avoiding a circular
 * dependency on AgentService.
 *
 * @param reason short human-readable cause, for logs only
 * @author MateClaw Team
 */
public record McpServerChangedEvent(String reason) {
}
