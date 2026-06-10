package vip.mate.workspace.document.event;

/**
 * Published whenever an agent's workspace file is created, updated, or deleted.
 * <p>
 * Workspace files (AGENTS.md, SOUL.md, PROFILE.md, MEMORY.md, structured/*.md)
 * are baked into the agent's system prompt when its runtime instance is built.
 * Listeners use this to invalidate the cached agent instance so memory edits
 * (tool writes, consolidation, cleanup) take effect on the next turn instead of
 * only after an agent config change or restart.
 *
 * @param agentId  the affected agent
 * @param filename the workspace file that changed
 */
public record WorkspaceFileChangedEvent(Long agentId, String filename) {
}
