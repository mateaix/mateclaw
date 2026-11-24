package vip.mate.tool.mcp.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 工具回调提供者
 * <p>
 * 将所有 active MCP clients 暴露的 tools 统一为 ToolCallbackProvider，
 * 供 ToolRegistry 收集并注入 AgentToolSet。
 * <p>
 * 每次调用 getToolCallbacks() 都会从 McpClientManager 获取最新的 active tools，
 * 因此新增/删除 MCP server 后无需重启即可生效。
 *
 * <p>RFC-052: tools listed in {@link McpReturnDirectProperties} are wrapped in
 * {@link ReturnDirectMcpToolCallback} so their results bypass the LLM context.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolCallbackProvider implements ToolCallbackProvider {

    private final McpClientManager mcpClientManager;
    private final McpReturnDirectProperties returnDirectProperties;

    @org.springframework.beans.factory.annotation.Autowired
    private McpWorkspaceAccess workspaceAccess;

    @Override
    public ToolCallback[] getToolCallbacks() {
        try {
            var callbacks = mcpClientManager.getAllToolCallbacks();
            if (!callbacks.isEmpty()) {
                log.debug("McpToolCallbackProvider providing {} tools from {} active MCP servers",
                        callbacks.size(), mcpClientManager.getActiveCount());
            }

            // Opt-in returnDirect wrapping. The decorator only changes
            // ToolMetadata.returnDirect(); guard/approval/observability still
            // see the original callback through the wrapper.
            //
            // Names registered by the manager are now prefixed
            // (mcp_<serverId>_<slug>_<hash6>) — but operators have been
            // configuring the return-direct list with raw upstream names
            // (e.g. `query_employee_salary`) since long before the prefix
            // existed. Match on EITHER form so an existing deployment's
            // sensitive-tool isolation doesn't silently regress when this
            // change rolls out: a tool counts as return-direct if its
            // configured token equals (a) the prefixed callback name OR
            // (b) the underlying raw tool name visible through the
            // PrefixedNameToolCallback wrapper.
            List<ToolCallback> wrapped = new ArrayList<>(callbacks.size());
            for (ToolCallback cb : callbacks) {
                String prefixed = cb.getToolDefinition() != null ? cb.getToolDefinition().name() : null;
                String raw = (cb instanceof PrefixedNameToolCallback w && w.getDelegate() != null
                        && w.getDelegate().getToolDefinition() != null)
                        ? w.getDelegate().getToolDefinition().name()
                        : null;
                if (returnDirectProperties.matches(prefixed, raw)) {
                    log.info("[McpToolCallbackProvider] wrapping MCP tool as returnDirect (prefixed='{}', raw='{}')",
                            prefixed, raw);
                    wrapped.add(new ReturnDirectMcpToolCallback(cb));
                } else {
                    wrapped.add(cb);
                }
            }
            if (workspaceAccess != null) {
                return wrapped.stream().map(cb -> (ToolCallback) new WorkspaceScopedMcpToolCallback(cb, workspaceAccess))
                        .toArray(ToolCallback[]::new);
            }
            return wrapped.toArray(new ToolCallback[0]);
        } catch (Exception e) {
            log.warn("Failed to collect MCP tool callbacks: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }
}
