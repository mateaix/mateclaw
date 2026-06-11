package vip.mate.tool.mcp.event;

/**
 * Published when a previously-connected MCP server's transport is detected to
 * have gone dead at runtime — either a live {@code listTools()} threw because
 * the held connection went stale, or a stdio subprocess exited on its own.
 *
 * <p>Unlike {@link McpServerChangedEvent} (which announces a state change that
 * already happened), this is a <em>request to heal</em>: {@code McpServerService}
 * listens, reloads the server config, and triggers an asynchronous reconnect
 * (debounced so a crash-looping server can't spin the reconnect executor).
 *
 * <p>Why an event rather than a direct call: {@code McpClientManager} /
 * {@code CwdAwareStdioClientTransport} are pure runtime components with no DB
 * access, and {@code McpServerService} already depends on the manager. Routing
 * the reconnect request through {@code ApplicationEventPublisher} keeps the
 * dependency one-directional and mirrors the existing
 * {@link McpServerChangedEvent} pattern.
 *
 * @param serverId the MCP server whose connection was lost
 * @param reason   short human-readable cause, for logs only
 * @author MateClaw Team
 */
public record McpConnectionLostEvent(Long serverId, String reason) {
}
