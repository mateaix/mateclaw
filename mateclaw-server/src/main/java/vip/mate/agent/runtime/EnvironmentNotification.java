package vip.mate.agent.runtime;

import java.time.Instant;

/**
 * A single environment-change notification destined for a running agent.
 *
 * <p>Produced by {@link EnvironmentEventRouter} when an MCP / skill event fires
 * during an in-flight conversation. Consumed by {@code ReasoningNode} (C4) which
 * drains the queue at the start of each reasoning turn and injects the
 * accumulated notifications as a single {@code SystemMessage} so the LLM sees
 * them alongside the progress ledger snapshot.
 *
 * <p>Notifications are ephemeral — they live only for the duration of a running
 * turn. If a conversation is not actively running when the event fires, the
 * notification is dropped (the next turn will rebuild the agent with fresh
 * state, so the LLM doesn't need a stale notification).
 *
 * @param type      event category — one of {@code mcp-changed}, {@code mcp-lost},
 *                  {@code mcp-removed}, {@code skill-removed}, {@code skill-updated}
 * @param message   human-readable, LLM-facing description of the change and
 *                  what the agent should do about it
 * @param timestamp when the event was observed by Java
 * @author MateClaw Team
 */
public record EnvironmentNotification(String type, String message, Instant timestamp) {
}
