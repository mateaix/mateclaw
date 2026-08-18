package vip.mate.workspace.document.event;

/**
 * Published whenever an agent's workspace file is created, updated, or deleted.
 * <p>
 * Shared workspace files (AGENTS.md, SOUL.md, PROFILE.md, MEMORY.md, ...)
 * are baked into the cached agent system prompt. Owner-scoped PERSONAL memory
 * rows are injected per turn instead, so they should not evict the agent cache.
 *
 * @param agentId             the affected agent
 * @param filename            the workspace file that changed
 * @param affectsSystemPrompt whether cached agent instances must be rebuilt
 */
public record WorkspaceFileChangedEvent(Long agentId, String filename, boolean affectsSystemPrompt) {

    public WorkspaceFileChangedEvent(Long agentId, String filename) {
        this(agentId, filename, true);
    }
}
