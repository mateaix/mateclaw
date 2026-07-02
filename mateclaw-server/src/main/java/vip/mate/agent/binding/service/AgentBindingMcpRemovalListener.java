package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.tool.mcp.event.McpServerRemovedEvent;
import vip.mate.tool.mcp.runtime.McpToolNameResolver;

import java.util.List;

/**
 * Drops {@code mate_agent_tool} rows that pointed at a now-removed MCP server's
 * tools (issue #127, MCP half).
 *
 * <p>MCP tool bindings are stored under the resolved name
 * {@code mcp_<serverId>_<slug>_<hash6>}. Deleting the server used to leave these
 * rows behind: the agent edit page kept showing the bindings and the user could
 * not clear them (the tools no longer exist in the live set, so the picker can't
 * render a row to uncheck). This mirrors the agent-skill cleanup for removed
 * skills.
 *
 * <p>Matching is done with an exact Java prefix rather than a SQL {@code LIKE}:
 * the literal underscores in {@code mcp_<serverId>_} are wildcards in {@code LIKE},
 * so {@code mcp_123_%} would also match server {@code 1234}'s tools. The coarse
 * query narrows to MCP bindings; the precise {@code startsWith} avoids deleting a
 * sibling server's rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentBindingMcpRemovalListener {

    private final AgentToolBindingMapper toolBindingMapper;

    @EventListener
    public void onMcpServerRemoved(McpServerRemovedEvent event) {
        if (event == null || event.serverId() == null) {
            return;
        }
        String serverPrefix = McpToolNameResolver.PREFIX + event.serverId() + "_";

        // Coarse-filter to MCP bindings in SQL, then match the exact server
        // prefix in Java to avoid the LIKE-underscore-wildcard false match.
        List<AgentToolBinding> candidates = toolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .likeRight(AgentToolBinding::getToolName, McpToolNameResolver.PREFIX));
        List<Long> orphanIds = candidates.stream()
                .filter(b -> belongsToServer(b.getToolName(), serverPrefix))
                .map(AgentToolBinding::getId)
                .toList();
        if (orphanIds.isEmpty()) {
            return;
        }
        int dropped = toolBindingMapper.delete(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .in(AgentToolBinding::getId, orphanIds));
        if (dropped > 0) {
            log.info("Cleaned {} agent-tool binding row(s) for removed MCP server {} (id={})",
                    dropped, event.serverName(), event.serverId());
        }
    }

    /** Exact prefix test: {@code mcp_123_x} belongs to server 123, {@code mcp_1234_x} does not. */
    static boolean belongsToServer(String toolName, String serverPrefix) {
        return toolName != null && toolName.startsWith(serverPrefix);
    }
}
