package vip.mate.agent.context;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.approval.grant.WorkspaceLookupCache;

/**
 * Resolves the workspace of the currently-executing conversation for the agent
 * runtime skill-resolution path.
 * <p>
 * The {@link ChatOrigin} carried in a tool's {@code ToolContext} usually already
 * holds the workspaceId (populated at the web / channel entry point). Some paths
 * — notably approval replay — carry a conversationId but a {@code null}
 * workspaceId; there we fall back to {@link WorkspaceLookupCache}, which maps a
 * conversationId to its owning workspace. When neither yields a workspace, the
 * result is {@code null}: callers must treat that conservatively (resolve only
 * builtin / global skills, never another workspace's skill).
 */
@Component
@RequiredArgsConstructor
public class AgentWorkspaceResolver {

    private final WorkspaceLookupCache workspaceLookupCache;

    /** Best-effort workspace id for the given origin; {@code null} if unresolved. */
    @Nullable
    public Long resolve(@Nullable ChatOrigin origin) {
        if (origin == null) {
            return null;
        }
        if (origin.workspaceId() != null) {
            return origin.workspaceId();
        }
        String conversationId = origin.conversationId();
        return conversationId != null ? workspaceLookupCache.resolveByConversation(conversationId) : null;
    }
}
