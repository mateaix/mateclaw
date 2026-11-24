package vip.mate.tool.mcp.runtime;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.exception.MateClawException;

/** Rechecks live ownership so a cached graph cannot retain access after migration. */
public final class WorkspaceScopedMcpToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final McpWorkspaceAccess access;

    public WorkspaceScopedMcpToolCallback(ToolCallback delegate, McpWorkspaceAccess access) {
        this.delegate = delegate;
        this.access = access;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return call(input, null); }
    @Override public String call(String input, ToolContext context) {
        var ref = McpToolNameResolver.parse(getToolDefinition().name());
        if (ref == null || !access.canUse(ref.serverId(), ChatOrigin.from(context).workspaceId())) {
            throw new MateClawException("err.workspace.forbidden", 403, "MCP tool is not available in this workspace");
        }
        return delegate.call(input, context);
    }
}
